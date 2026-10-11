from types import SimpleNamespace
from pathlib import Path
import sys

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pluxy.config import PluxyConfig  # noqa: E402
from pluxy.metadata import MetadataProvider, select_trailer  # noqa: E402
from pluxy.models import MediaItem, MovieMetadata  # noqa: E402
from pluxy.catalogue import public_item  # noqa: E402


def provider(tmp_path):
    config = PluxyConfig()
    config.metadata.tmdb_api_key = "test-only"
    return MetadataProvider(SimpleNamespace(cfg=config), tmp_path)


def episode(number=1, season=1):
    return MediaItem(id=f"s{season}e{number}", title="Series", path="test.mkv", container="mkv", size=1,
                     duration=60, kind="series", series_name="Series", series_key="series", season=season, episode=number)


def mock_tmdb(monkeypatch, handler):
    monkeypatch.setattr("pluxy.metadata.tmdb_client", lambda *_args, **_kwargs:
                        httpx.Client(transport=httpx.MockTransport(handler)))


def test_correct_season_and_episode_share_one_request_without_sharing_the_still(tmp_path, monkeypatch):
    p = provider(tmp_path)
    calls = []
    def handler(request):
        calls.append(request.url.path)
        season = int(request.url.path.rsplit("/", 1)[1])
        return httpx.Response(200, json={"episodes": [
            {"episode_number": 1, "still_path": f"/s{season}e1.jpg"},
            {"episode_number": 2, "still_path": f"/s{season}e2.jpg"},
            {"episode_number": 3, "still_path": None}]})
    mock_tmdb(monkeypatch, handler)
    base = MovieMetadata(title="Series", tmdb_id=42, matched=True, source="tmdb_tv", poster_url="https://image.tmdb.org/t/p/w500/show.jpg")
    monkeypatch.setattr(p, "_fetch_tmdb_tv", lambda *_args: base)
    first, second, missing, other = [p.get(item) for item in [episode(), episode(2), episode(3), episode(1, 2)]]
    assert len(calls) == 2
    assert first.episode_still_url.endswith("s1e1.jpg")
    assert second.episode_still_url.endswith("s1e2.jpg")
    assert other.episode_still_url.endswith("s2e1.jpg")
    assert missing.episode_still_url is None
    assert base.episode_still_url is None
    public = public_item(episode(2), second, {})
    assert public.poster_url == base.poster_url
    assert public.episode_still_url == second.episode_still_url
    assert public.thumbnail_url.startswith("/api/library/items/")
    assert p.snapshot()[1][episode(2).id].episode_still_url == second.episode_still_url


def test_transient_season_failure_keeps_metadata_and_is_retried_without_refetching_the_show(tmp_path, monkeypatch):
    p = provider(tmp_path)
    requests = []
    def handler(request):
        requests.append(request)
        if len(requests) == 1:
            return httpx.Response(503)
        return httpx.Response(200, json={"episodes": [{"episode_number": 1, "still_path": "/first.jpg"}]})
    mock_tmdb(monkeypatch, handler)
    base = MovieMetadata(title="Series", tmdb_id=42, matched=True, source="tmdb_tv", trailer_url="https://www.youtube.com/watch?v=abcdefghijk")
    monkeypatch.setattr(p, "_fetch_tmdb_tv", lambda *_args: base)
    first = p.get(episode())
    assert first.matched and first.trailer_url == base.trailer_url and first.episode_still_url is None
    assert p.get(episode()).episode_still_url is None and len(requests) == 1
    p._season_cache = type(p._season_cache)((key, (0, value[1])) for key, value in p._season_cache.items())
    assert p.get(episode()).episode_still_url.endswith("first.jpg") and len(requests) == 2


def test_manual_show_match_preserved_but_each_episode_has_its_own_still(tmp_path, monkeypatch):
    p = provider(tmp_path)
    mock_tmdb(monkeypatch, lambda _request: httpx.Response(200, json={"episodes": [
        {"episode_number": 1, "still_path": "/one.jpg"}, {"episode_number": 2, "still_path": "/two.jpg"}]}))
    monkeypatch.setattr(p, "_fetch_tmdb_tv", lambda *_args, **_kwargs:
                        MovieMetadata(title="Selected series", tmdb_id=99, matched=True, source="tmdb_tv"))
    p.choose_match(episode(), 99, [episode(), episode(2)])
    monkeypatch.setattr(p, "_fetch_tmdb_tv", lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("Manual show must remain")))
    assert p.get(episode(), force=True).episode_still_url.endswith("one.jpg")
    assert p.get(episode(2), force=True).episode_still_url.endswith("two.jpg")
    assert p.get(episode(2), force=True).title == "Selected series"
    assert p.get(episode(3)).tmdb_id == 99


def test_unidentified_episode_does_not_receive_a_guessed_image(tmp_path, monkeypatch):
    p = provider(tmp_path)
    mock_tmdb(monkeypatch, lambda _request: (_ for _ in ()).throw(AssertionError("No network needed")))
    base = MovieMetadata(title="Unknown", matched=True, tmdb_id=42, episode_still_url="https://image.tmdb.org/t/p/w780/wrong.jpg")
    assert p._episode_artwork(episode().model_copy(update={"episode": None}), base).episode_still_url is None
    assert p._episode_artwork(episode(), base.model_copy(update={"matched": False})).episode_still_url is None
    p.cfgm.cfg.metadata.tmdb_api_key = ""
    assert p._episode_artwork(episode(), base).episode_still_url == base.episode_still_url


def test_only_real_trailers_and_teasers_with_valid_keys_are_selected():
    videos = [dict(site="YouTube", type="Featurette", key="behindscene", iso_639_1="fr", official=True),
              dict(site="YouTube", type="Trailer", key="bad/key????", iso_639_1="fr"),
              dict(site="YouTube", type="Trailer", key="english0001", iso_639_1="en", official=True),
              dict(site="YouTube", type="Trailer", key="francais001", iso_639_1="fr", official=False),
              dict(site="YouTube", type="Trailer", key="francais002", iso_639_1="fr", official=True)]
    assert select_trailer(videos) == "francais002"
    assert select_trailer(videos[:2]) is None


def test_english_backup_does_not_discard_the_french_synopsis(tmp_path, monkeypatch):
    p = provider(tmp_path)
    calls = []
    def handler(request):
        calls.append(request.url.path)
        if request.url.path.endswith("/videos"):
            return httpx.Response(200, json={"results": [{"site": "YouTube", "type": "Trailer", "key": "english0001", "official": True, "iso_639_1": "en"}]})
        return httpx.Response(200, json={"id": 42, "name": "Series", "overview": "Synopsis français", "videos": {"results": []}})
    mock_tmdb(monkeypatch, handler)
    meta = p._fetch_tmdb_tv("Series", "test-only", "fr-FR", selected_id=42)
    assert meta.overview == "Synopsis français"
    assert meta.trailer_url.endswith("english0001")
    assert calls == ["/3/tv/42", "/3/tv/42/videos"]
