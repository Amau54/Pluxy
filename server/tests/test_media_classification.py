from types import SimpleNamespace

import httpx
import pytest
from PIL import Image

from pluxy.video_kind import classify_video


@pytest.mark.parametrize("name,folders,category,duration,title,expected", [
    ("VID_20241008_152300", ["Films"], "auto", 100, "", "clip"),
    ("Piscine 2024", ["Vacances"], "auto", 6000, "", "clip"),
    ("Notre voyage", ["Médias"], "auto", 300, "", "clip"),
    ("Example.S02E03", ["Médias"], "auto", 0, "", "series"),
    ("Example", ["Séries", "Saison 1"], "auto", 0, "", "series"),
    ("Example (2024) 1080p", [], "auto", 0, "", "movie"),
    ("Example", ["Films"], "auto", 0, "", "movie"),
    ("Example", [], "auto", 6000, "Titre intégré", "movie"),
    ("2024 anniversaire", [], "auto", 90, "", "clip"),
    ("Example.S02E03", ["Séries"], "clip", 6000, "", "clip"),
    ("VID_1234", ["Vacances"], "movie", 60, "", "movie"),
    ("Example", [], "series", 0, "", "series"),
])
def test_auto_and_manual_video_classification(name, folders, category, duration, title, expected):
    assert classify_video(name, folders, category, duration, title) == expected


def test_category_change_reclassifies_cached_file_and_preserves_discovery(tmp_path, monkeypatch):
    from pluxy.config import ConfigManager
    from pluxy.library import Library
    from pluxy.models import MediaInfo
    root = tmp_path / "Médias"
    root.mkdir()
    file = root / "Example.mp4"
    file.write_bytes(b"video")
    (tmp_path / "data").mkdir()
    manager = ConfigManager(tmp_path / "data", tmp_path / "absent.json")
    manager.update({"server": {"media_dirs": [str(root)], "media_dir_types": {str(root): "movie"}}})
    monkeypatch.setattr("pluxy.library.probe", lambda *args: MediaInfo(path=str(file), container="mp4", duration=120))
    library = Library(manager, tmp_path / "data")
    library.scan()
    old = library.all()[0]
    assert old.kind == "movie" and old.source_name == "Médias" and old.source_id
    manager.update({"server": {"media_dir_types": {str(root): "clip"}}})
    monkeypatch.setattr("pluxy.library.probe", lambda *args: pytest.fail("Le fichier inchangé reste en cache"))
    library.scan()
    new = library.all()[0]
    assert new.kind == "clip" and new.id == old.id and new.added_at == old.added_at
    assert new.relative_path == "Example.mp4"


def test_personal_video_does_not_inherit_tmdb_poster():
    from pluxy.catalogue import public_item
    from pluxy.models import MediaItem, MovieMetadata
    item = MediaItem(id="test", title="Voyage", path="private", kind="clip", container="mp4", duration=30, size=1)
    public = public_item(item, MovieMetadata(title="Un film", poster_url="https://image.tmdb.org/a", matched=True), {})
    assert public.path == "" and public.poster_url is None and public.metadata_title is None
    assert public.thumbnail_url == "/api/library/items/test/thumbnail?v=2"


def test_tmdb_accepts_key_and_bearer_without_exposing_credentials(monkeypatch):
    from pluxy.tmdb_auth import tmdb_client, tmdb_status
    with tmdb_client("a" * 32) as client:
        request = client.build_request("GET", "https://api.themoviedb.org/3/authentication")
        assert request.url.params["api_key"] == "a" * 32 and "Authorization" not in request.headers
    with tmdb_client("Bearer eyJ.test") as client:
        request = client.build_request("GET", "https://api.themoviedb.org/3/authentication")
        assert "api_key" not in request.url.params and request.headers["Authorization"] == "Bearer eyJ.test"
    monkeypatch.setattr("pluxy.tmdb_auth.tmdb_client", lambda *args, **kwargs: httpx.Client(
        transport=httpx.MockTransport(lambda request: httpx.Response(401, json={"success": False}))))
    assert tmdb_status("sensitive") == "invalid_key"


def test_thumbnail_selects_visible_frame_after_black_intro(tmp_path, monkeypatch):
    from pluxy.config import PluxyConfig
    from pluxy.models import MediaItem
    from pluxy.thumbnails import VideoThumbnailer, frame_score
    source = tmp_path / "clip.mp4"
    source.write_bytes(b"video")
    colors = iter([(0, 0, 0), (80, 150, 200), (255, 255, 255)])
    calls = []
    def capture(command, **kwargs):
        color = next(colors)
        Image.new("RGB", (64, 36), color).save(command[-1], format="JPEG")
        calls.append(command)
        return SimpleNamespace(returncode=0)
    monkeypatch.setattr("pluxy.thumbnails.subprocess.run", capture)
    item = MediaItem(id="clip", title="Voyage", path=str(source), kind="clip", container="mp4", size=5, duration=10)
    thumbs = VideoThumbnailer(PluxyConfig(), tmp_path)
    result = thumbs.jpeg(item)
    with Image.open(result) as frame:
        assert frame_score(frame) > frame_score(Image.new("RGB", (64, 36), "black"))
        assert frame.getpixel((0, 0))[1] in range(145, 156)
    assert thumbs.jpeg(item) == result and len(calls) == 3


def test_diagnostics_is_local_only(tmp_path):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from pluxy.api.routes_settings import router
    app = FastAPI()
    app.include_router(router)
    with TestClient(app, client=("192.168.1.20", 4000)) as client:
        assert client.get("/api/settings/diagnostics").status_code == 403


def test_clip_thumbnail_is_served_by_http(tmp_path, monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from pluxy.api.routes_library import router
    from pluxy.models import MediaItem
    app = FastAPI()
    app.include_router(router)
    image = tmp_path / "frame.jpg"
    Image.new("RGB", (64, 36), (80, 150, 200)).save(image)
    item = MediaItem(id="clip", title="Clip", path="private", kind="clip", container="mp4", duration=30, size=1)
    app.state.pluxy = SimpleNamespace(library=SimpleNamespace(get=lambda _: item),
                                     thumbnails=SimpleNamespace(jpeg=lambda _: image))
    with TestClient(app) as client:
        response = client.get("/api/library/items/clip/thumbnail?v=2")
        assert response.status_code == 200 and response.content.startswith(b"\xff\xd8")
        assert response.headers["content-type"] == "image/jpeg"


def test_tmdb_title_check_keeps_accents_and_rejects_partial_personal_name():
    from pluxy.metadata import _confident
    assert _confident("Été à Paris", "Ete a Paris")
    assert _confident("Interstellar", "Interstelar")
    assert _confident("Up", "Up")
    assert not _confident("A la piscine avec la famille", "La piscine")
    assert not _confident("Voyage", "V")
