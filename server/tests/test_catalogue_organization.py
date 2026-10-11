"""Dates d'import, albums et navigation filtrée sur des catalogues volumineux."""
from __future__ import annotations

import asyncio
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

def test_dirty_season_packs_share_identity_and_keep_season_numbers():
    from pluxy.library import _parse_series
    seasons = [_parse_series(Path(f"SERIES/Boreal.2013.MULTI.1080p.WEB-DL.H264-GROUP/Boreal.S{s:02}.MULTI.1080p.WEB-DL.H264-GROUP/Boreal.S{s:02}E01.mkv")) for s in range(1, 9)]
    assert {name for name, _, _, _ in seasons} == {"Boreal"}
    assert {key for _, key, _, _ in seasons} == {"boreal"}
    assert [season for _, _, season, _ in seasons] == list(range(1, 9))

def test_episode_subdirectories_share_series_identity():
    from pluxy.library import _parse_series
    episodes = [_parse_series(Path(f"SERIES/House.of.the.Dragon.S02.MULTi.HDR.DV.2160p.WEB.x265-FW/House.of.the.Dragon.S02E{ep:02}.PROPER.MULTi.HDR.DV.2160p.WEB.x265-FW/House.of.the.Dragon.S02E{ep:02}.mkv")) for ep in range(1, 9)]
    assert {name for name, _, _, _ in episodes} == {"House of the Dragon"}
    assert {key for _, key, _, _ in episodes} == {"houseofthedragon"}
    assert [episode for _, _, season, episode in episodes if season == 2] == list(range(1, 9))
    assert _parse_series(Path("SERIES/Another.Show.2x03.1080p-WEB/Another.Show.2x03.mkv")) == ("Another Show", "anothershow", 2, 3)

@pytest.mark.parametrize("width,height,expected", [(3840,1608,"4k"), (3836,1604,"4k"), (1920,800,"1080p"), (1916,804,"1080p"), (1280,536,"720p"), (640,360,"sd")])
def test_cropped_video_resolution(width, height, expected):
    from pluxy.catalogue import resolution
    from pluxy.models import MediaItem
    item = MediaItem(id="film", title="Film", path="film.mkv", container="mkv", size=1, duration=120, width=width, height=height, kind="movie")
    assert resolution(item) == expected

def test_explicit_photo_and_music_roots_scan_only_their_media_even_with_video_extensions(tmp_path, monkeypatch):
    from pluxy.config import ServerCfg
    state, root, _ = make_state(tmp_path, monkeypatch)
    (root / "image.png").write_bytes(b"not-a-real-image")
    (root / "cover.jpg").write_bytes(b"album-art-in-explicit-photo-folder")
    (root / "music.mp3").write_bytes(b"audio")
    (root / "film.mkv").write_bytes(b"film")
    state.cfgm.cfg.server.scan_extensions = [".mkv"]
    state.cfgm.cfg.server.media_dir_types[str(root)] = "photo"
    state.library.scan()
    assert {item.title for item in state.library.all()} == {"image", "cover"}
    assert {item.kind for item in state.library.all()} == {"photo"}
    state.cfgm.cfg.server.media_dir_types[str(root)] = "audio"
    state.library.scan()
    assert [item.kind for item in state.library.all()] == ["audio"]
    assert ServerCfg(media_dir_types={"photos":"photo", "music":"audio"}).media_dir_types["photos"] == "photo"

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


def make_state(tmp_path, monkeypatch):
    from pluxy.config import PluxyConfig
    from pluxy.library import Library
    from pluxy.metadata import MetadataProvider
    from pluxy.models import MediaInfo
    from pluxy.watchstate import WatchStore

    root = tmp_path / "media"
    root.mkdir()
    cfg = PluxyConfig()
    cfg.server.media_dirs = [str(root)]
    cfgm = SimpleNamespace(cfg=cfg)
    calls = []
    def analyse(_binary, path):
        calls.append(path)
        return MediaInfo(path=path, container=Path(path).suffix[1:], duration=120,
                         tags={"artist": "Artiste", "album": "Album", "track": "2/12", "disc": "1/2"})
    monkeypatch.setattr("pluxy.library.probe", analyse)
    state = SimpleNamespace(library=Library(cfgm, tmp_path), metadata=MetadataProvider(cfgm, tmp_path),
                            watch=WatchStore(tmp_path), cfgm=cfgm)
    return state, root, calls


def test_import_date_is_first_discovery_and_survives_replacement(tmp_path, monkeypatch):
    state, root, calls = make_state(tmp_path, monkeypatch)
    movie = root / "Old movie.mkv"
    movie.write_bytes(b"aaaa")
    old = datetime(2001, 1, 1, tzinfo=timezone.utc).timestamp()
    os.utime(movie, (old, old))
    monkeypatch.setattr("pluxy.library.time.time", lambda: 2_000_000_000.0)
    state.library.scan()
    item = state.library.all()[0]
    assert item.added_at == 2_000_000_000 and item.modified_at == old
    state.watch.set(item.id, 12_000, 120_000)
    movie.write_bytes(b"bbbb")
    os.utime(movie, (old + 5, old + 5))
    state.library.scan()
    updated = state.library.all()[0]
    assert len(calls) == 2 and updated.added_at == item.added_at and updated.modified_at == old + 5
    assert state.watch.get(item.id)["position_ms"] == 12_000


def test_legacy_index_preserves_added_order_and_reuses_probe(tmp_path, monkeypatch):
    state, root, calls = make_state(tmp_path, monkeypatch)
    movie = root / "Existing.mkv"
    movie.write_bytes(b"film")
    modified = movie.stat().st_mtime
    item_id = state.library.make_id(str(movie))
    state.library.legacy_index_path.write_text(json.dumps([{
        "id": item_id, "title": "Existing", "path": str(movie), "container": "mkv",
        "size": 4, "duration": 120, "added_at": modified,
    }]), encoding="utf-8")
    from pluxy.library import Library
    migrated = Library(state.cfgm, tmp_path)
    assert migrated.get(item_id).added_at == migrated.get(item_id).modified_at == modified
    migrated.scan()
    assert calls == [] and migrated.get(item_id).added_at == modified


def test_config_migration_expands_only_the_old_default_extensions():
    from pluxy.config import ConfigManager, VIDEO_SCAN_EXTENSIONS
    old, changed = ConfigManager._migrate({"schema_version": 3, "server": {"scan_extensions": VIDEO_SCAN_EXTENSIONS[:]}})
    assert changed and ".jpg" in old["server"]["scan_extensions"] and ".flac" in old["server"]["scan_extensions"]
    custom, changed = ConfigManager._migrate({"schema_version": 3, "server": {"scan_extensions": [".mkv"]}})
    assert changed and custom["server"]["scan_extensions"] == [".mkv"]


def test_filename_year_is_available_before_metadata_and_survives_empty_metadata(tmp_path, monkeypatch):
    from pluxy.catalogue import Catalogue, Query
    from pluxy.models import MovieMetadata
    state, root, _calls = make_state(tmp_path, monkeypatch)
    (root / "Un film (2020).1080p.mkv").write_bytes(b"film")
    state.library.scan()
    item = state.library.all()[0]
    assert item.year == 2020
    state.metadata._save(item.id, MovieMetadata(title="Un film", year=None, matched=False))
    items, total, _revision = Catalogue(state.library, state.metadata, state.watch).page(Query(year=2020), 0, 100)
    assert total == 1 and items[0].year == 2020


def test_matched_metadata_title_is_searchable_exposed_and_sorted_without_changing_filename(tmp_path, monkeypatch):
    from pluxy.catalogue import Catalogue, Query
    from pluxy.models import MovieMetadata
    state, root, _calls = make_state(tmp_path, monkeypatch)
    (root / "Release.original.2020.1080p.mkv").write_bytes(b"film")
    state.library.scan()
    original = state.library.all()[0]
    state.metadata._save(original.id, MovieMetadata(title="Été à Paris", matched=True))
    public, total, _revision = Catalogue(state.library, state.metadata, state.watch).page(Query(q="ete paris"), 0, 100)
    assert total == 1 and public[0].metadata_title == "Été à Paris"
    assert public[0].title == original.title and public[0].path == ""
    assert state.library.get(original.id).path == original.path
    state.metadata._save(original.id, MovieMetadata(title="Nom provisoire", matched=False))
    public, total, _revision = Catalogue(state.library, state.metadata, state.watch).page(Query(q="provisoire"), 0, 100)
    assert total == 0


@pytest.mark.parametrize("offset, expected", [
    (None, datetime(2020, 8, 7, 11, 12, 13, tzinfo=timezone.utc).timestamp()),
    ("+02:00", datetime(2020, 8, 7, 9, 12, 13, tzinfo=timezone.utc).timestamp()),
])
def test_photo_exif_date_dimensions_and_relative_folder(tmp_path, monkeypatch, offset, expected):
    from PIL import Image
    state, root, calls = make_state(tmp_path, monkeypatch)
    folder = root / "Vacances" / "Jour 1"
    folder.mkdir(parents=True)
    exif = Image.Exif()
    details = {36867: "2020:08:07 11:12:13"}
    if offset:
        details[36881] = offset
    exif[34665] = details
    Image.new("RGB", (32, 20)).save(folder / "Photo.jpg", exif=exif)
    Image.new("RGB", (12, 10)).save(folder / "Sans date.png")
    assert state.library.scan() == 2
    photo = next(item for item in state.library.all() if item.title == "Photo")
    assert photo.kind == "photo" and photo.captured_at == expected
    assert photo.width == 32 and photo.height == 20
    assert photo.folder == "Vacances/Jour 1" and photo.relative_path == "Vacances/Jour 1/Photo.jpg"
    assert photo.folder_id == state.library.make_id(str(folder))
    assert next(item for item in state.library.all() if item.title == "Sans date").captured_at is None
    assert calls == []
    state.library.scan()
    assert calls == [] and state.library.get(photo.id).captured_at == expected


def test_audio_album_artist_track_disc_and_mime(tmp_path, monkeypatch):
    from pluxy.api.routes_stream import _mime_for
    state, root, calls = make_state(tmp_path, monkeypatch)
    (root / "02 piste.flac").write_bytes(b"music")
    assert state.library.scan() == 1
    item = state.library.all()[0]
    assert (item.kind, item.artist, item.album, item.track_number, item.disc_number) == ("audio", "Artiste", "Album", 2, 1)
    assert _mime_for("flac") == "audio/flac" and _mime_for("jpeg") == "image/jpeg"
    assert len(calls) == 1


def test_photo_thumbnail_is_small_oriented_cached_and_avoids_ffmpeg(tmp_path, monkeypatch):
    from PIL import Image
    from pluxy.thumbnails import VideoThumbnailer
    state, root, _calls = make_state(tmp_path, monkeypatch)
    exif = Image.Exif()
    exif[274] = 6
    Image.new("RGB", (1200, 600), (80, 120, 160)).save(root / "Portrait.jpg", exif=exif)
    state.library.scan()
    item = state.library.all()[0]
    def no_ffmpeg(*_args, **_kwargs):
        raise AssertionError("Les miniatures photo n'ont pas besoin de FFmpeg")
    monkeypatch.setattr("pluxy.thumbnails.subprocess.run", no_ffmpeg)
    thumbnailer = VideoThumbnailer(state.cfgm.cfg, tmp_path)
    target = thumbnailer.jpeg(item)
    assert target and target.stat().st_size < 100_000
    with Image.open(target) as result:
        assert result.format == "JPEG" and result.width == 256 and result.height == 512
    monkeypatch.setattr(thumbnailer, "_photo", no_ffmpeg)
    assert thumbnailer.jpeg(item) == target


def test_audio_folder_cover_is_cached_and_not_added_to_the_photo_library(tmp_path, monkeypatch):
    from PIL import Image
    from pluxy.thumbnails import VideoThumbnailer
    from pluxy.catalogue import Catalogue, Query
    state, root, _calls = make_state(tmp_path, monkeypatch)
    (root / "Piste.flac").write_bytes(b"audio")
    Image.new("RGBA", (900, 900), (30, 90, 120, 128)).save(root / "cover.png")
    state.library.scan()
    assert len(state.library.all()) == 1 and state.library.all()[0].kind == "audio"
    item = state.library.all()[0]
    def no_ffmpeg(*_args, **_kwargs):
        raise AssertionError("Une pochette locale n'a pas besoin de FFmpeg")
    monkeypatch.setattr("pluxy.thumbnails.subprocess.run", no_ffmpeg)
    thumbnailer = VideoThumbnailer(state.cfgm.cfg, tmp_path)
    target = thumbnailer.jpeg(item)
    assert target
    with Image.open(target) as image:
        assert image.mode == "RGB" and image.size == (512, 512)
    monkeypatch.setattr(thumbnailer, "_photo", no_ffmpeg)
    assert thumbnailer.jpeg(item) == target
    public = Catalogue(state.library, state.metadata, state.watch).page(Query(kind="audio"), 0, 100)[0][0]
    assert public.thumbnail_url == f"/api/library/items/{item.id}/thumbnail?v=2"


def test_audio_cover_outside_the_authorized_folder_is_ignored(tmp_path, monkeypatch):
    from PIL import Image
    from pluxy.thumbnails import VideoThumbnailer
    state, root, _calls = make_state(tmp_path, monkeypatch)
    audio = root / "Piste.flac"
    audio.write_bytes(b"audio")
    cover = root / "cover.jpg"
    Image.new("RGB", (30, 30)).save(cover)
    outside = tmp_path / "private.jpg"
    Image.new("RGB", (30, 30)).save(outside)
    original = Path.resolve
    def outgoing_symlink(path, *args, **kwargs):
        return outside if path == cover else original(path, *args, **kwargs)
    monkeypatch.setattr(Path, "resolve", outgoing_symlink)
    assert VideoThumbnailer._audio_cover(audio) is None


def test_missing_ffmpeg_audio_art_is_not_reprobed_on_every_request(tmp_path, monkeypatch):
    from pluxy.thumbnails import VideoThumbnailer
    state, root, _calls = make_state(tmp_path, monkeypatch)
    (root / "Piste.flac").write_bytes(b"audio")
    state.library.scan()
    calls = []
    def unavailable(*args, **kwargs):
        calls.append(args)
        raise FileNotFoundError("FFmpeg absent")
    monkeypatch.setattr("pluxy.thumbnails.subprocess.run", unavailable)
    thumbnailer = VideoThumbnailer(state.cfgm.cfg, tmp_path)
    item = state.library.all()[0]
    assert thumbnailer.jpeg(item) is None
    assert thumbnailer.jpeg(item) is None
    assert len(calls) == 1 and "-ss" not in calls[0][0]


def test_embedded_audio_art_uses_the_first_image_without_seek(tmp_path, monkeypatch):
    from PIL import Image
    from pluxy.thumbnails import VideoThumbnailer
    state, root, _calls = make_state(tmp_path, monkeypatch)
    (root / "Piste.flac").write_bytes(b"audio")
    state.library.scan()
    calls = []
    def extract(command, **_kwargs):
        calls.append(command)
        Image.new("RGB", (128, 128)).save(command[-1], format="JPEG")
        return SimpleNamespace(returncode=0)
    monkeypatch.setattr("pluxy.thumbnails.subprocess.run", extract)
    target = VideoThumbnailer(state.cfgm.cfg, tmp_path).jpeg(state.library.all()[0])
    assert target and len(calls) == 1 and "-ss" not in calls[0]
    assert calls[0][calls[0].index("-map") + 1] == "0:v:0"


def test_http_audio_thumbnail_returns_jpeg_etag_and_cache_headers(tmp_path, monkeypatch):
    from PIL import Image
    from pluxy.main import app
    from pluxy.thumbnails import VideoThumbnailer
    state, root, _calls = make_state(tmp_path, monkeypatch)
    (root / "Piste.flac").write_bytes(b"audio")
    Image.new("RGB", (90, 90), (20, 60, 100)).save(root / "folder.jpg")
    state.library.scan()
    item = state.library.all()[0]
    state.thumbnails = VideoThumbnailer(state.cfgm.cfg, tmp_path)
    monkeypatch.setattr(app.state, "pluxy", state, raising=False)
    async def exercise():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost") as client:
            first = await client.get(f"/api/library/items/{item.id}/thumbnail")
            assert first.status_code == 200 and first.content.startswith(b"\xff\xd8")
            assert first.headers["content-type"] == "image/jpeg"
            assert first.headers["etag"].startswith(f'"thumb-{item.id}-')
            assert first.headers["cache-control"] == "private, max-age=86400"
            second = await client.get(f"/api/library/items/{item.id}/thumbnail")
            assert second.status_code == 200 and second.content == first.content
            assert second.headers["etag"] == first.headers["etag"]
    asyncio.run(exercise())


def test_metadata_snapshot_discards_stale_ids_and_loads_reintroduced_media(tmp_path, monkeypatch):
    from pluxy.models import MovieMetadata
    state, _root, _calls = make_state(tmp_path, monkeypatch)
    state.metadata._save("deleted", MovieMetadata(title="Deleted", genres=["Action"]))
    state.metadata._save("active", MovieMetadata(title="Active", genres=["Drame"]))
    _revision, snapshot = state.metadata.snapshot(frozenset({"active"}))
    assert set(snapshot) == {"active"}
    _revision, snapshot = state.metadata.snapshot(frozenset({"active", "deleted"}))
    assert set(snapshot) == {"active", "deleted"} and snapshot["deleted"].genres == ["Action"]


def test_catalogue_revision_is_invalid_after_server_restart(tmp_path, monkeypatch):
    from pluxy.catalogue import Catalogue, Query
    from pluxy.metadata import MetadataProvider
    from pluxy.watchstate import WatchStore
    state, _root, _calls = make_state(tmp_path, monkeypatch)
    populate(state)
    catalogue = Catalogue(state.library, state.metadata, state.watch)
    revision = catalogue.page(Query(), 0, 1)[2]
    restarted = Catalogue(state.library, MetadataProvider(state.cfgm, tmp_path), WatchStore(tmp_path))
    with pytest.raises(ValueError):
        restarted.page(Query(), 1, 1, revision)


def populate(state):
    from pluxy.models import MediaItem, MovieMetadata
    specs = [
        ("a", "Été à Paris", "movie", 2020, ["Comédie", "Drame"], 80 * 60, 1920, 1080, False, 10),
        ("b", "Action", "movie", 2021, ["Action"], 120 * 60, 3840, 1600, True, 20),
        ("c", "Court métrage", "movie", 2020, ["Drame"], 15 * 60, 1280, 720, False, 30),
        ("d", "Inconnu", "movie", None, [], 0, None, None, False, 40),
        ("e", "Série S01E01", "series", 2021, ["Drame"], 45 * 60, 720, 480, False, 50),
        ("f", "Photo", "photo", None, [], 0, 4000, 3000, False, 60),
        ("g", "Musique", "audio", None, [], 150, None, None, False, 70),
    ]
    for item_id, title, kind, year, genres, duration, width, height, hdr, added in specs:
        state.library._items[item_id] = MediaItem(
            id=item_id, title=title, path="C:/private/secret.mkv", container="mkv", size=4,
            kind=kind, duration=duration, width=width, height=height, is_hdr=hdr, added_at=added,
            artist="Artiste été" if kind == "audio" else None, album="Album" if kind == "audio" else None,
            folder="Vacances", series_key="serie" if kind == "series" else None,
            external_subs=["C:/private/secret.srt"],
        )
        state.metadata._save(item_id, MovieMetadata(title=title, year=year, genres=genres, matched=True))
    state.library._refresh_snapshot()
    state.watch.set("a", 100_000, 100_000)
    state.watch.set("b", 20_000, 100_000)


@pytest.mark.parametrize("filters, expected", [
    ({"kind": "movie"}, ["b", "c", "a", "d"]),
    ({"q": "ete paris"}, ["a"]),
    ({"q": "ete", "kind": "audio"}, ["g"]),
    ({"watched": "watched"}, ["a"]),
    ({"watched": "in_progress"}, ["b"]),
    ({"kind": "movie", "watched": "unwatched"}, ["b", "c", "d"]),
    ({"genres": ("comedie", "action")}, ["b", "a"]),
    ({"kind": "movie", "genres": ("drame",), "year": 2020, "duration_max_minutes": 90}, ["c", "a"]),
    ({"resolution": "4k"}, ["b"]),
    ({"resolution": "1080p"}, ["a"]),
    ({"resolution": "720p"}, ["c"]),
    ({"resolution": "sd"}, ["e"]),
    ({"hdr_only": True}, ["b"]),
    ({"kind": "movie", "sort": "added", "descending": True}, ["d", "c", "b", "a"]),
    ({"kind": "movie", "sort": "duration"}, ["c", "a", "b", "d"]),
])
def test_combined_query_filters(tmp_path, monkeypatch, filters, expected):
    from pluxy.catalogue import Catalogue, Query
    state, _root, _calls = make_state(tmp_path, monkeypatch)
    populate(state)
    items, total, _revision = Catalogue(state.library, state.metadata, state.watch).page(Query(**filters), 0, 100)
    assert [item.id for item in items] == expected and total == len(expected)
    assert all(item.path == "" and item.external_subs == [] for item in items)


def test_revision_conflicts_on_scan_metadata_or_watch_publication(tmp_path, monkeypatch):
    from pluxy.catalogue import Catalogue, Query
    from pluxy.models import MovieMetadata
    state, _root, _calls = make_state(tmp_path, monkeypatch)
    populate(state)
    catalogue = Catalogue(state.library, state.metadata, state.watch)
    first, total, revision = catalogue.page(Query(kind="movie"), 0, 2)
    second, _total, _revision = catalogue.page(Query(kind="movie"), 2, 2, revision)
    assert total == 4 and not set(item.id for item in first).intersection(item.id for item in second)
    state.watch.set("c", 15_000, 100_000)
    with pytest.raises(ValueError):
        catalogue.page(Query(), 0, 2, revision)
    revision = catalogue.page(Query(), 0, 2)[2]
    state.metadata._save("c", MovieMetadata(title="Court", year=2010, genres=["Action"]))
    with pytest.raises(ValueError):
        catalogue.page(Query(), 0, 2, revision)
    revision = catalogue.page(Query(), 0, 2)[2]
    state.library._items.pop("c")
    state.library._refresh_snapshot()
    with pytest.raises(ValueError):
        catalogue.page(Query(), 0, 2, revision)


def test_facets_and_home_include_first_episode_resume_without_photos_audio(tmp_path, monkeypatch):
    from pluxy.catalogue import Catalogue
    state, _root, _calls = make_state(tmp_path, monkeypatch)
    populate(state)
    state.watch.set("e", 30_000, 100_000)
    catalogue = Catalogue(state.library, state.metadata, state.watch)
    facets = catalogue.facets("movie")
    assert facets["query_supported"] and facets["total"] == 4
    assert facets["counts"] == {"movie": 4, "series": 1, "clip": 0, "photo": 1, "audio": 1}
    assert facets["genres"] == ["Action", "Comédie", "Drame"] and facets["years"] == [2021, 2020]
    assert facets["resolutions"] == ["4k", "1080p", "720p"]
    home = catalogue.home()
    assert set(item.id for item in home["items"]) == {"a", "b", "c", "d", "e"}
    assert next(item for item in home["items"] if item.id == "e").in_progress


def test_warm_large_catalogue_queries_do_not_read_or_stat_metadata_files(tmp_path, monkeypatch):
    from pluxy.catalogue import Catalogue, Query
    from pluxy.models import MediaItem, MovieMetadata
    state, _root, _calls = make_state(tmp_path, monkeypatch)
    for index in range(10_000):
        item_id = str(index)
        state.library._items[item_id] = MediaItem(id=item_id, title=f"Film {index:05d}", path="", container="mkv",
                                                size=1, duration=5400, width=1920, height=1080)
    state.library._refresh_snapshot()
    state.metadata._catalogue = {str(index): MovieMetadata(title=f"Film {index}", genres=["Drame"], year=2020)
                                 for index in range(10_000)}
    catalogue = Catalogue(state.library, state.metadata, state.watch)
    assert catalogue.page(Query(), 0, 100)[1] == 10_000
    def no_file_access(*_args, **_kwargs):
        raise AssertionError("Le parcours ne doit relire aucun fichier de métadonnées")
    monkeypatch.setattr(Path, "stat", no_file_access)
    monkeypatch.setattr(Path, "read_text", no_file_access)
    monkeypatch.setattr(state.metadata, "cached", no_file_access)
    query = Query(kind="movie", genres=("Drame",), year=2020, resolution="1080p", duration_max_minutes=90)
    values, total, revision = catalogue.page(query, 0, 100)
    assert len(values) == 100 and total == 10_000
    assert len(catalogue.page(query, 9_900, 100, revision)[0]) == 100


def test_alphabetical_pages_use_displayed_title_and_ignore_accents(tmp_path, monkeypatch):
    from pluxy.catalogue import Catalogue, Query
    from pluxy.models import MovieMetadata
    state, _root, _calls = make_state(tmp_path, monkeypatch)
    populate(state)
    state.metadata._save("b", MovieMetadata(title="Évasion", matched=True))
    catalogue = Catalogue(state.library, state.metadata, state.watch)
    first, total, revision = catalogue.page(Query(kind="movie", title_prefix="E"), 0, 1)
    last = catalogue.page(Query(kind="movie", title_prefix="E"), 1, 1, revision)[0]
    assert total == 2 and [item.id for item in first + last] == ["a", "b"]
    assert catalogue.page(Query(kind="movie", title_prefix="A"), 0, 100)[1] == 0


def test_http_query_paging_facets_and_validation(tmp_path, monkeypatch):
    from pluxy.main import app
    state, _root, _calls = make_state(tmp_path, monkeypatch)
    populate(state)
    monkeypatch.setattr(app.state, "pluxy", state, raising=False)
    async def exercise():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost") as client:
            first = await client.get("/api/library/items/page", params={"kind": "movie", "limit": 2})
            assert first.status_code == 200 and first.json()["total"] == 4
            second = await client.get("/api/library/items/page", params={"kind": "movie", "limit": 2,
                                     "offset": first.json()["next_offset"], "revision": first.json()["revision"]})
            assert second.status_code == 200 and second.json()["next_offset"] is None
            genres = await client.get("/api/library/items/page", params=[("genre", "Comédie"), ("genre", "Action")])
            assert genres.json()["total"] == 2
            assert (await client.get("/api/library/items/page", params={"kind": "movie", "title_prefix": "E"})).json()["total"] == 1
            assert (await client.get("/api/library/facets")).json()["query_supported"]
            assert len((await client.get("/api/library/home")).json()["items"]) == 5
            for params in ({"sort": "unknown"}, {"resolution": "8k"}, {"year": 0}, {"genre": ["x"] * 33}, {"title_prefix": "AB"}):
                assert (await client.get("/api/library/items/page", params=params)).status_code == 422
            state.watch.set("c", 20_000, 100_000)
            assert (await client.get("/api/library/items/page", params={"revision": first.json()["revision"]})).status_code == 409
    asyncio.run(exercise())
