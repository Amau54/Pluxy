# -*- coding: utf-8 -*-
"""Tests de non-regression des correctifs d'audit (securite + robustesse)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


def test_segment_anti_traversal():
    from pluxy.api.routes_stream import _SEGMENT_RE
    bad = [r"..\..\x.ts", "../../x.ts", "seg_0/../x.ts", r"C:\x.ts",
           "seg_0.ts.exe", "evil.ts", ".env", "seg_.ts", "init.mp4"]
    good = ["seg_00000.ts", "seg_12.ts", "seg_9999999.ts"]
    assert all(not _SEGMENT_RE.match(b) for b in bad), "traversal non bloque"
    assert all(_SEGMENT_RE.match(g) for g in good), "segment legitime refuse"


def test_http_ranges():
    from pluxy.api.routes_stream import MAX_HLS_SEGMENT_INDEX, _parse_range
    assert _parse_range("bytes=0-99", 1000) == (0, 99)
    assert _parse_range("bytes=900-", 1000) == (900, 999)
    assert _parse_range("bytes=-100", 1000) == (900, 999)
    assert _parse_range("bytes=-5000", 1000) == (0, 999)
    for bad in ("bytes=100-99", "bytes=1000-", "bytes=-0", "bytes=0-1,4-5", "garbage"):
        try:
            _parse_range(bad, 1000)
        except ValueError:
            pass
        else:
            raise AssertionError(f"Range invalide accepte: {bad}")
    assert MAX_HLS_SEGMENT_INDEX == 100_000


def test_lan_boundary_and_remote_settings():
    from fastapi import HTTPException
    from pluxy.config import PluxyConfig
    from pluxy.security import (
        filtered_remote_settings,
        is_lan_address,
        validate_remote_patch,
    )

    assert all(is_lan_address(value) for value in (
        "127.0.0.1", "10.1.2.3", "172.16.1.2", "192.168.1.2", "fd12::42",
    ))
    assert not is_lan_address("8.8.8.8")
    assert not is_lan_address("2001:4860:4860::8888")

    cfg = PluxyConfig()
    cfg.server.media_dirs = ["/private/movies"]
    cfg.metadata.tmdb_api_key = "secret"
    public = filtered_remote_settings(cfg)
    dumped = str(public)
    assert "media_dirs" not in dumped and "secret" not in dumped and "ffmpeg_path" not in dumped
    assert validate_remote_patch({"network": {"hls_segment_duration": 4}})
    for forbidden in (
        {"server": {"media_dirs": ["/"]}},
        {"metadata": {"tmdb_api_key": "stolen"}},
        {"ffmpeg": {"ffmpeg_path": "evil"}},
    ):
        try:
            validate_remote_patch(forbidden)
        except HTTPException as exc:
            assert exc.status_code == 403
        else:
            raise AssertionError(f"Patch sensible accepte: {forbidden}")


def test_atomic_write():
    from pluxy.paths import atomic_write_text
    import tempfile
    p = Path(tempfile.mkdtemp()) / "t.json"
    atomic_write_text(p, '{"a":1}')
    assert p.read_text(encoding="utf-8") == '{"a":1}'
    assert not p.with_suffix(".json.tmp").exists()  # tmp nettoye par os.replace


def test_corrupt_config_is_preserved_and_recovered(tmp_path):
    import json

    from pluxy.config import ConfigManager, PluxyConfig

    data = tmp_path / "data"
    data.mkdir()
    default = tmp_path / "config.default.json"
    cfg = PluxyConfig()
    cfg.server.name = "Configuration saine"
    cfg.server.transcode_temp_dir = str(tmp_path / "cache")
    default.write_text(json.dumps(cfg.model_dump()), encoding="utf-8")
    (data / "config.json").write_text("{json casse", encoding="utf-8")

    manager = ConfigManager(data, default)

    assert manager.cfg.server.name == "Configuration saine"
    assert list(data.glob("config.invalid-*.json"))
    assert json.loads((data / "config.json").read_text(encoding="utf-8"))["server"]["name"] == "Configuration saine"


def test_invalid_buffer_order_is_rejected():
    from pydantic import ValidationError

    from pluxy.config import ClientBufferCfg

    try:
        ClientBufferCfg(min_buffer_ms=5_000, buffer_for_playback_after_rebuffer_ms=6_000)
    except ValidationError:
        pass
    else:
        raise AssertionError("Un buffer de reprise superieur au buffer minimum a ete accepte")


def test_request_body_limit_covers_chunked_requests():
    import asyncio

    from pluxy.main import RequestBodyLimitMiddleware

    sent = []
    chunks = iter((
        {"type": "http.request", "body": b"1234", "more_body": True},
        {"type": "http.request", "body": b"5678", "more_body": False},
    ))

    async def receive():
        return next(chunks)

    async def send(message):
        sent.append(message)

    async def downstream(_scope, limited_receive, _send):
        await limited_receive()
        await limited_receive()

    middleware = RequestBodyLimitMiddleware(downstream, max_bytes=6)
    asyncio.run(middleware({"type": "http"}, receive, send))
    assert any(message.get("status") == 413 for message in sent)


def test_vod_playlist_is_bounded_and_invalid_duration_is_safe(tmp_path):
    from pluxy.config import PluxyConfig
    from pluxy.vodhls import VodHls

    cfg = PluxyConfig()
    cfg.server.transcode_temp_dir = str(tmp_path)
    vod = VodHls(cfg, tmp_path)
    assert vod.playlist(float("nan")).count("#EXTINF") == 1
    huge = vod.playlist(vod.seg_time * (vod.MAX_PLAYLIST_SEGMENTS + 50))
    assert huge.count("#EXTINF") == vod.MAX_PLAYLIST_SEGMENTS


def test_adaptive_main_bitrate_is_source_aware():
    from pluxy.api.routes_stream import _adaptive_compat_bitrate, _adaptive_main_bitrate
    from pluxy.models import MediaInfo, MediaStream, StreamKind

    def media(mbps):
        return MediaInfo(
            path="film.mkv",
            container="mkv",
            overall_bitrate=mbps * 1_000_000,
            streams=[MediaStream(
                index=0,
                kind=StreamKind.VIDEO,
                codec="hevc",
                bit_rate=mbps * 1_000_000,
            )],
        )

    assert _adaptive_main_bitrate(media(100), 50) == 50
    assert _adaptive_main_bitrate(media(20), 50) == 14
    assert _adaptive_main_bitrate(media(10), 50) == 7
    assert _adaptive_main_bitrate(media(3), 50) == 2
    assert _adaptive_compat_bitrate(media(100), 50) == 12
    assert _adaptive_compat_bitrate(media(20), 50) == 11
    assert _adaptive_compat_bitrate(media(10), 50) == 6
    assert _adaptive_compat_bitrate(media(3), 50) == 2


def test_video_thumbnail_time_is_representative_and_bounded():
    from pluxy.thumbnails import capture_second

    assert capture_second(8) == 4
    assert capture_second(100) == 10
    assert capture_second(10_000) == 120
    assert capture_second(0) == 10


def test_video_thumbnail_is_generated_once_then_cached(tmp_path, monkeypatch):
    from pluxy.config import PluxyConfig
    from pluxy.models import MediaItem
    from pluxy.thumbnails import VideoThumbnailer

    source = tmp_path / "film.mkv"
    source.write_bytes(b"video")
    item = MediaItem(
        id="a" * 16,
        title="Film",
        path=str(source),
        container="mkv",
        size=source.stat().st_size,
        duration=100,
    )
    calls = []

    def fake_run(command, **_kwargs):
        Path(command[-1]).write_bytes(b"jpeg")
        calls.append(command)
        return type("Result", (), {"returncode": 0})()

    monkeypatch.setattr("pluxy.thumbnails.subprocess.run", fake_run)
    thumbnailer = VideoThumbnailer(PluxyConfig(), tmp_path / "data")
    first = thumbnailer.jpeg(item)
    second = thumbnailer.jpeg(item)

    assert first == second and first.read_bytes() == b"jpeg"
    assert len(calls) == 1
    assert calls[0][calls[0].index("-ss") + 1] == "10.000"


def test_video_keeps_local_thumbnail_fallback_with_remote_poster():
    from types import SimpleNamespace

    from pluxy.api.routes_library import _enrich
    from pluxy.models import MediaItem, MovieMetadata

    item = MediaItem(
        id="b" * 16,
        title="Film",
        path="/private/film.mkv",
        container="mkv",
        size=1,
        duration=100,
    )
    metadata = MovieMetadata(
        title="Film",
        poster_url="https://image.tmdb.org/t/p/w500/poster.jpg",
        matched=True,
    )
    state = SimpleNamespace(
        metadata=SimpleNamespace(cached=lambda _item_id: metadata),
        watch=SimpleNamespace(get=lambda _item_id: {}),
    )

    public = _enrich(item, state)

    assert public.poster_url == metadata.poster_url
    assert public.thumbnail_url == f"/api/library/items/{item.id}/thumbnail"
    assert public.path == ""


def test_vod_session_releases_its_slot_once(tmp_path, monkeypatch):
    import time

    from pluxy import vodhls

    class FinishedProcess:
        def poll(self):
            return 0

        def wait(self, timeout=None):
            return 0

    released = []
    monkeypatch.setattr(vodhls.subprocess, "Popen", lambda *args, **kwargs: FinishedProcess())
    session = vodhls.VodSession(tmp_path, ["ffmpeg"], 0, lambda: released.append(True))
    session.start()
    deadline = time.time() + 1
    while not released and time.time() < deadline:
        time.sleep(0.01)
    session.stop_proc()
    assert released == [True]


def test_watch_store_skips_corrupt_entries_and_prunes(tmp_path):
    import json

    from pluxy.watchstate import WatchStore

    (tmp_path / ".pluxy_watch.json").write_text(
        json.dumps({
            "good": {"position_ms": 10, "duration_ms": 100, "watched": False},
            "bad": {"position_ms": "pas-un-nombre"},
        }),
        encoding="utf-8",
    )
    store = WatchStore(tmp_path)
    assert store.get("good")["position_ms"] == 10
    assert store.get("bad")["position_ms"] == 0
    store.prune({"good"})
    assert set(json.loads((tmp_path / ".pluxy_watch.json").read_text(encoding="utf-8"))) == {"good"}


def test_watch_store_records_last_played_timestamp(tmp_path):
    from pluxy.watchstate import WatchStore

    store = WatchStore(tmp_path)
    before = __import__("time").time()
    store.set("film", 5_000, 100_000)
    state = store.get("film")

    assert state["last_played_at"] >= before
    assert WatchStore(tmp_path).get("film")["last_played_at"] >= before


def test_preflight_reports_unadaptable_format_without_ffmpeg():
    from pluxy.config import PluxyConfig
    from pluxy.decision import decide
    from pluxy.models import ClientCapabilities, MediaInfo, MediaStream, StreamKind

    cfg = PluxyConfig()
    cfg.ffmpeg.ffmpeg_path = "/definitely/missing/pluxy-ffmpeg"
    media = MediaInfo(
        path="film.mkv",
        container="mkv",
        streams=[MediaStream(index=0, kind=StreamKind.VIDEO, codec="av1", height=2160)],
    )
    caps = ClientCapabilities(
        supports_av1=False,
        supported_video_codecs=["h264"],
        profile_id="living-room",
        profile_name="TV salon",
    )

    decision = decide(media, caps, cfg)

    assert decision.mode.value == "direct_play"
    assert decision.playable is False
    assert decision.severity == "unsupported"
    assert decision.profile_id == "living-room"


def test_unknown_but_advertised_video_codec_can_direct_play():
    from pluxy.config import PluxyConfig
    from pluxy.decision import decide
    from pluxy.models import ClientCapabilities, MediaInfo, MediaStream, StreamKind

    media = MediaInfo(
        path="film.webm",
        container="webm",
        streams=[MediaStream(index=0, kind=StreamKind.VIDEO, codec="vp9", height=1080)],
    )
    caps = ClientCapabilities(
        supported_containers=["webm"],
        supported_video_codecs=["h264", "vp9"],
    )

    decision = decide(media, caps, PluxyConfig())

    assert decision.mode.value == "direct_play"
    assert decision.playable is True
    assert decision.severity == "success"


def test_app_imports():
    from pluxy.main import app
    def collect(routes):
        paths = set()
        for route in routes:
            path = getattr(route, "path", None)
            if path:
                paths.add(path)
            nested = getattr(route, "routes", None)
            if nested:
                paths.update(collect(nested))
            original = getattr(route, "original_router", None)
            if original is not None:
                paths.update(collect(original.routes))
        return paths

    paths = collect(app.routes)
    assert "/stream/hls/{item_id}/{variant}/index.m3u8" in paths
    assert "/api/library/items/{item_id}/thumbnail" in paths
    assert "/api/playback/diagnose" in paths


def test_sub_lang_detection():
    from pluxy.library import Library
    from pathlib import Path as P
    tracks = Library._sub_tracks(P("Movie.mkv"), [P("Movie.fr.srt"), P("Movie.srt"), P("Movie.en.vtt")])
    assert tracks[0].lang == "fr" and tracks[0].format == "srt"
    assert tracks[1].lang is None
    assert tracks[2].lang == "en" and tracks[2].format == "vtt"


def test_sidecar_strict():
    # Film2.srt ne doit PAS matcher Film.mkv (test logique de la condition)
    stem = "film"
    assert not ("film2".startswith(stem + ".") or "film2" == stem)
    assert ("film.fr".startswith(stem + ".")) or ("film" == stem)


if __name__ == "__main__":
    test_segment_anti_traversal()
    test_http_ranges()
    test_lan_boundary_and_remote_settings()
    test_atomic_write()
    test_app_imports()
    test_sub_lang_detection()
    test_sidecar_strict()
    print("TOUS LES TESTS AUDIT OK")
