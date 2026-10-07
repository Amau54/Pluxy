"""Contracts and failure scenarios for the deep PC audit corrections."""
from __future__ import annotations

import asyncio
import json
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


@pytest.fixture
def state(tmp_path, monkeypatch):
    from pluxy.config import ConfigManager, PluxyConfig
    from pluxy.library import Library
    from pluxy.main import app
    from pluxy.metadata import MetadataProvider
    from pluxy.models import MediaItem
    from pluxy.vodhls import VodHls
    from pluxy.watchstate import WatchStore

    cfg = PluxyConfig()
    cfg.server.transcode_temp_dir = str(tmp_path / "cache")
    cfg.server.transcode_min_free_mb = 0
    default = tmp_path / "default.json"
    default.write_text(cfg.model_dump_json(), encoding="utf-8")
    cfgm = ConfigManager(tmp_path, default)
    source = tmp_path / "Film.mkv"
    source.write_bytes(b"synthetic media")
    item = MediaItem(id="a" * 16, title="Film", path=str(source), container="mkv", size=source.stat().st_size,
                     duration=120, external_subs=[str(tmp_path / "Film.fr.srt")])
    library = Library(cfgm, tmp_path)
    library._items[item.id] = item
    library._refresh_snapshot()
    decisions = {}
    value = SimpleNamespace(cfgm=cfgm, library=library, item=item, watch=WatchStore(tmp_path),
                            metadata=MetadataProvider(cfgm, tmp_path), vod=VodHls(cfg, tmp_path),
                            active_port=8420, active_host="0.0.0.0", probe_slots=threading.BoundedSemaphore(4),
                            hls_request_slots=threading.BoundedSemaphore(8),
                            hls_executor=ThreadPoolExecutor(max_workers=8, thread_name_prefix="hls-test"),
                            remember_decision=lambda client, item_id, decision: decisions.update({(client, item_id): decision}),
                            decision_for=lambda client, item_id: decisions.get((client, item_id)))
    monkeypatch.setattr(app.state, "pluxy", value, raising=False)
    try:
        yield value
    finally:
        try:
            value.vod.stop_all()
        finally:
            value.hls_executor.shutdown(wait=True, cancel_futures=True)


async def request(method, path, *, peer="127.0.0.1", base_url="http://localhost:8420", **kwargs):
    from pluxy.main import app
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=(peer, 54321)), base_url=base_url) as client:
        return await client.request(method, path, **kwargs)


def test_watch_read_permission_keeps_existing_history(tmp_path, monkeypatch):
    from pluxy import config
    from pluxy.watchstate import WatchStore
    path = tmp_path / ".pluxy_watch.json"
    path.write_text('{"film":{"position_ms":120,"duration_ms":1000}}', encoding="utf-8")
    before = path.read_bytes()
    original = Path.read_text
    attempts = []
    def refused(target, *args, **kwargs):
        if target == path:
            attempts.append(True)
            raise PermissionError(13, "Locked")
        return original(target, *args, **kwargs)
    monkeypatch.setattr(config, "os", SimpleNamespace(name="nt"))
    monkeypatch.setattr(config.time, "sleep", lambda _: None)
    monkeypatch.setattr(Path, "read_text", refused)
    with pytest.raises(PermissionError):
        WatchStore(tmp_path)
    assert len(attempts) == 6 and path.read_bytes() == before


def test_watch_disk_failure_is_http503_and_preserves_memory(state, monkeypatch):
    from pluxy import paths
    state.watch.set(state.item.id, 12000, 120000)
    before = state.watch.path.read_bytes()
    monkeypatch.setattr(paths, "atomic_write_text", lambda *_: (_ for _ in ()).throw(OSError("disk full")))
    response = asyncio.run(request("POST", f"/api/playback/progress/{state.item.id}",
                                   json={"position_ms": 50000, "duration_ms": 120000}))
    assert response.status_code == 503
    assert state.watch.get(state.item.id)["position_ms"] == 12000
    assert state.watch.path.read_bytes() == before


def test_progress_sequences_retired_sessions_and_backwards_seek_survive_reload(tmp_path):
    from pluxy.watchstate import WatchStore
    store = WatchStore(tmp_path)
    assert store.set("film", 120, 1000, "session_A", 2)
    assert not store.set("film", 110, 1000, "session_A", 1)
    assert store.set("film", 40, 1000, "session_A", 3)  # intentional backwards seek
    assert store.set("film", 80, 1000, "session_B", 0)
    store = WatchStore(tmp_path)
    assert not store.set("film", 150, 1000, "session_A", 99)
    assert not store.set("film", 90, 1000, "session_B", 0)
    assert store.get("film")["position_ms"] == 80
    assert store.set("film", 50, 1000)  # legacy does not reset sequencing
    assert not store.set("film", 99, 1000, "session_B", 0)
    assert store.set("film", 30, 1000, "session_B", 1)


@pytest.mark.parametrize("body", [
    {"session_id": "session_A"}, {"sequence": 1}, {"session_id": "x", "sequence": 1},
    {"session_id": "session_A", "sequence": -1}, {"session_id": "session_A", "sequence": True},
])
def test_progress_pair_and_bounds_rejected(state, body):
    response = asyncio.run(request("POST", f"/api/playback/progress/{state.item.id}", json=body))
    assert response.status_code == 422 and state.watch.get(state.item.id)["position_ms"] == 0


def test_corrupt_watch_backup_and_get_returns_copy(tmp_path):
    from pluxy.watchstate import WatchStore
    path = tmp_path / ".pluxy_watch.json"
    path.write_text("{broken", encoding="utf-8")
    store = WatchStore(tmp_path)
    assert list(tmp_path.glob(".pluxy_watch.json.invalid-*"))[0].read_text(encoding="utf-8") == "{broken"
    store.set("film", 10, 1000)
    public = store.get("film")
    public["position_ms"] = 999
    assert store.get("film")["position_ms"] == 10


def test_failed_probe_retries_after_deadline_without_source_change(tmp_path, monkeypatch):
    from pluxy.config import PluxyConfig
    from pluxy.library import Library
    from pluxy.models import MediaInfo
    media = tmp_path / "media"
    media.mkdir()
    source = media / "Film.mkv"
    source.write_bytes(b"fixture")
    cfg = PluxyConfig()
    cfg.server.media_dirs = [str(media)]
    library = Library(SimpleNamespace(cfg=cfg), tmp_path)
    clock = [100.0]
    monkeypatch.setattr("pluxy.library.time.time", lambda: clock[0])
    monkeypatch.setattr("pluxy.library.probe", lambda *_: (_ for _ in ()).throw(RuntimeError("offline")))
    library.scan()
    calls = []
    def recovered(_, path):
        calls.append(path)
        return MediaInfo(path=path, container="mkv", duration=120)
    monkeypatch.setattr("pluxy.library.probe", recovered)
    library.scan()
    assert not calls and library.all()[0].duration == 0
    clock[0] = 161
    library.scan()
    assert len(calls) == 1 and library.all()[0].duration == 120


def test_metadata_negative_ttl_config_change_and_atomic_cache(state, monkeypatch):
    from pluxy.models import MovieMetadata
    state.cfgm.update({"metadata": {"tmdb_api_key": "synthetic"}})
    clock = [100.0]
    monkeypatch.setattr("pluxy.metadata.time.time", lambda: clock[0])
    calls = []
    def fetch(*args):
        calls.append(args)
        if len(calls) == 1:
            raise RuntimeError("temporary outage")
        return MovieMetadata(title="Film", matched=True)
    monkeypatch.setattr(state.metadata, "_fetch_tmdb", fetch)
    assert not state.metadata.get(state.item).matched
    assert not state.metadata.get(state.item).matched and len(calls) == 1
    clock[0] = 161
    assert state.metadata.get(state.item).matched and len(calls) == 2
    state.cfgm.update({"metadata": {"language": "en-US"}})
    assert state.metadata.get(state.item).matched and len(calls) == 3
    assert not list(state.metadata.meta_dir.glob("*.tmp"))


def test_thumbnail_single_flight_uses_unique_temp_and_releases_slot(state, tmp_path, monkeypatch):
    from pluxy.thumbnails import VideoThumbnailer, ThumbnailBusy
    thumbs = VideoThumbnailer(state.cfgm.cfg, tmp_path)
    entered, release = threading.Event(), threading.Event()
    calls = []
    def generate(command, **kwargs):
        calls.append(command[-1])
        entered.set()
        assert release.wait(2)
        from PIL import Image
        Image.new("RGB", (24, 24), (120, 150, 180)).save(command[-1], format="JPEG")
        return SimpleNamespace(returncode=0)
    monkeypatch.setattr("pluxy.thumbnails.subprocess.run", generate)
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(thumbs.jpeg, state.item)
        assert entered.wait(2)
        with pytest.raises(ThumbnailBusy):
            thumbs.jpeg(state.item)
        release.set()
        result = first.result(timeout=2)
    assert result is not None and thumbs.jpeg(state.item) == result and len(calls) == 3
    assert Path(calls[0]).name != result.with_suffix(".part").name
    assert not list(thumbs.cache_dir.glob("*.part"))


def register(state, token, bitrate=50, cfg=None):
    from pluxy.models import MediaInfo, PlaybackDecision, PlaybackMode
    media = MediaInfo(path=state.item.path, container="mkv", duration=120)
    decision = PlaybackDecision(mode=PlaybackMode.TRANSCODE, target_bitrate_mbps=bitrate)
    state.vod.register(state.item.id, "main", media, decision, token, cfg or state.cfgm.cfg)
    directory = state.vod._dir(state.vod._sid(state.item.id, "main", token))
    directory.mkdir(parents=True, exist_ok=True)
    (directory / ".pluxy-session.json").write_text(json.dumps({"app": "pluxy", "version": 1, "owner_pid": os.getpid()}), encoding="utf-8")
    segment = directory / "seg_00000.ts"
    segment.write_bytes(str(bitrate).encode())
    return segment


def test_hls_sessions_independent_cache_source_change_and_stop(state):
    first = register(state, "session_A", 50)
    second = register(state, "session_B", 8)
    assert first != second
    assert state.vod.get_segment(state.cfgm.cfg, state.item.id, "main", 0, "session_A").read_bytes() == b"50"
    state.vod.cleanup_item(state.item.id, "session_B")
    assert first.exists() and not second.exists()
    stamp = Path(state.item.path).stat().st_mtime_ns
    os.utime(state.item.path, ns=(stamp + 1_000_000, stamp + 1_000_000))
    replacement = register(state, "session_A", 50)
    assert replacement != first and not first.exists()


def test_hls_in_use_segment_survives_cleanup_until_released(state):
    segment = register(state, "session_A")
    assert state.vod.pin_segment(segment)
    state.vod.cleanup_item(state.item.id, "session_A")
    assert segment.exists()
    state.vod.release_segment(segment)
    assert not segment.exists()


def test_hls_startup_purges_only_owned_orphan_directories(tmp_path, monkeypatch):
    from pluxy.config import PluxyConfig
    from pluxy.vodhls import VodHls
    cfg = PluxyConfig()
    cfg.server.transcode_temp_dir = str(tmp_path)
    orphan = tmp_path / ("vods_" + "a" * 16 + "_main")
    orphan.mkdir()
    (orphan / "seg_00000.ts").write_bytes(b"orphan")
    (orphan / ".pluxy-session.json").write_text('{"app":"pluxy","version":1,"owner_pid":0}', encoding="utf-8")
    unrelated = tmp_path / "personal"
    unrelated.mkdir()
    (unrelated / "keep.mkv").write_bytes(b"source")
    active = tmp_path / "vods_active"
    active.mkdir()
    (active / ".pluxy-session.json").write_text(json.dumps({"app": "pluxy", "version": 1, "owner_pid": 123}), encoding="utf-8")
    monkeypatch.setattr(VodHls, "_process_alive", staticmethod(lambda pid: pid == 123))
    vod = VodHls(cfg, tmp_path)
    assert not orphan.exists() and unrelated.exists() and active.exists()
    vod.stop_all()


def test_hls_disk_reserve_rejects_start_without_process(state, monkeypatch):
    register(state, "session_A")
    state.cfgm.update({"server": {"transcode_min_free_mb": 100}})
    register(state, "session_B", cfg=state.cfgm.cfg)
    monkeypatch.setattr("pluxy.vodhls.shutil.disk_usage", lambda _: SimpleNamespace(free=1))
    called = []
    monkeypatch.setattr(state.vod, "_make_session", lambda *args: called.append(args))
    assert state.vod.get_segment(state.cfgm.cfg, state.item.id, "main", 1, "session_B") is None
    assert not called and state.vod.cache_error


def test_hls_query_propagates_and_current_config_only_affects_new_session(state):
    register(state, "session_A")
    state.cfgm.update({"network": {"hls_segment_duration": 5}})
    register(state, "session_B", cfg=state.cfgm.cfg)
    first = asyncio.run(request("GET", f"/stream/hls/{state.item.id}/main/index.m3u8?session=session_A"))
    second = asyncio.run(request("GET", f"/stream/hls/{state.item.id}/main/index.m3u8?session=session_B"))
    assert "#EXT-X-TARGETDURATION:3" in first.text and "seg_00000.ts?session=session_A" in first.text
    assert "#EXT-X-TARGETDURATION:5" in second.text
    conflict = asyncio.run(request("GET", f"/stream/hls/{state.item.id}/main/index.m3u8?session=session_A",
                                   headers={"X-Pluxy-Session": "session_B"}))
    assert conflict.status_code == 400


def test_hls_bounded_waits_leave_control_available(state, monkeypatch):
    from pluxy.main import app
    register(state, "session_A")
    entered, release = threading.Event(), threading.Event()
    count = [0]
    lock = threading.Lock()
    def waiting(*args):
        with lock:
            count[0] += 1
            if count[0] == 8:
                entered.set()
        assert release.wait(3)
        return None
    monkeypatch.setattr(state.vod, "get_segment", waiting)
    async def exercise():
        # Deliberately keep the shared executor small: eight HLS waits must
        # run separately and leave even asyncio.to_thread control work free.
        asyncio.get_running_loop().set_default_executor(executor)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=("127.0.0.1", 54321)), base_url="http://localhost") as client:
            tasks = [asyncio.create_task(client.get(f"/stream/hls/{state.item.id}/main/seg_00001.ts?session=session_A")) for _ in range(8)]
            try:
                deadline = time.monotonic() + 2
                while not entered.is_set() and time.monotonic() < deadline:
                    await asyncio.sleep(0.01)
                assert entered.is_set()
                health, settings, progress = await asyncio.gather(
                    asyncio.wait_for(client.get("/health"), 0.5),
                    asyncio.wait_for(client.patch("/api/settings", json={"server": {"name": "Control available"}}), 0.5),
                    asyncio.wait_for(client.post(f"/api/playback/progress/{state.item.id}",
                                                 json={"position_ms": 12000, "duration_ms": 120000}), 0.5),
                )
                assert health.status_code == settings.status_code == progress.status_code == 200
                assert settings.json()["server"]["name"] == "Control available"
                assert progress.json()["position_ms"] == 12000
                assert (await client.get(f"/stream/hls/{state.item.id}/main/seg_00001.ts?session=session_A")).status_code == 503
            finally:
                release.set()
                # Always drain the requests before closing their client/loop,
                # including when entry or control assertions fail.
                results = await asyncio.gather(*tasks, return_exceptions=True)
            assert all(isinstance(result, httpx.Response) and result.status_code == 503 for result in results)
            state.hls_executor.shutdown(wait=True, cancel_futures=True)
            # Failed submissions must return their admission slot every time,
            # rather than silently exhaust the eight-slot budget on shutdown.
            for _ in range(9):
                stopped = await client.get(f"/stream/hls/{state.item.id}/main/seg_00001.ts?session=session_A")
                assert stopped.status_code == 503 and stopped.json()["detail"] == "Attentes HLS indisponibles"
    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="shared-control-test") as executor:
        asyncio.run(exercise())


def test_public_paths_pagination_revision_and_active_port(state, monkeypatch):
    from pluxy.models import MediaInfo
    response = asyncio.run(request("GET", "/api/library/items", peer="192.168.1.20"))
    assert response.json()[0]["path"] == "" and response.json()[0]["external_subs"] == []
    monkeypatch.setattr("pluxy.api.routes_stream._safe_probe_limited", lambda *_: MediaInfo(path=state.item.path, container="mkv"))
    decision = asyncio.run(request("POST", "/api/playback/decide", peer="192.168.1.20", json={"item_id": state.item.id}))
    assert decision.status_code == 200 and decision.json()["media"]["path"] == ""
    page = asyncio.run(request("GET", "/api/library/items/page?limit=1")).json()
    assert page["total"] == 1 and page["next_offset"] is None and page["revision"]
    assert asyncio.run(request("GET", "/api/library/items/page?limit=501")).status_code == 422
    state.library._items.clear()
    state.library._refresh_snapshot()
    assert asyncio.run(request("GET", "/api/library/items/page?revision=" + page["revision"])).status_code == 409
    state.cfgm.update({"server": {"port": 8422}})
    info = asyncio.run(request("GET", "/api/server/info")).json()
    assert info["port"] == 8420 and info["configured_port"] == 8422 and info["restart_required"]


def test_untrusted_host_and_cross_site_browser_writes_rejected(state):
    hostile = asyncio.run(request("GET", "/api/settings", base_url="http://attacker.invalid"))
    assert hostile.status_code == 400
    write = asyncio.run(request("POST", f"/api/playback/progress/{state.item.id}", headers={"Origin": "https://attacker.invalid"},
                                json={"position_ms": 1200, "duration_ms": 120000}))
    assert write.status_code == 403
    native = asyncio.run(request("POST", f"/api/playback/progress/{state.item.id}", peer="192.168.1.20",
                                 json={"position_ms": 1200, "duration_ms": 120000}))
    assert native.status_code == 200


def test_scan_write_failure_keeps_previous_snapshot_and_reports_error(state, monkeypatch):
    from pluxy.models import MediaInfo
    media_dir = Path(state.item.path).parent / "new-media"
    media_dir.mkdir()
    (media_dir / "New.mkv").write_bytes(b"video")
    state.cfgm.update({"server": {"media_dirs": [str(media_dir)]}})
    before = state.library.page(0, 500)
    monkeypatch.setattr("pluxy.library.probe", lambda _, path: MediaInfo(path=path, container="mkv", duration=120))
    monkeypatch.setattr(state.library, "_save_index", lambda *_: (_ for _ in ()).throw(OSError("disk full")))
    with pytest.raises(OSError):
        state.library.scan()
    assert state.library.page(0, 500) == before
    assert state.library.scan_status["status"] == "error" and not state.library.is_scanning


def test_partial_scan_does_not_prune_existing_watch_history(state, monkeypatch):
    from pluxy.state import AppState
    state.cfgm.update({"metadata": {"auto_fetch_on_scan": False}})
    state._closing = threading.Event()
    state._job_lock = threading.RLock()
    state._scan_thread = None
    state.watch.set("missing_source", 1000, 10000)
    def partial_scan(**kwargs):
        state.library.scan_status = {"status": "warning", "unavailable_sources": 1}
    monkeypatch.setattr(state.library, "scan", partial_scan)
    assert AppState.scan_async(state)
    deadline = time.monotonic() + 2
    while state._scan_thread is not None and time.monotonic() < deadline:
        time.sleep(0.01)
    assert state.watch.get("missing_source")["position_ms"] == 1000


def test_hls_quota_and_owner_pid_guard(state):
    from pluxy.vodhls import VodHls
    path = register(state, "session_A")
    with path.open("wb") as handle:
        handle.seek(17 * 1024 * 1024)
        handle.write(b"x")
    cfg = state.cfgm.cfg.model_copy(deep=True)
    cfg.server.transcode_cache_quota_mb = 16
    assert not state.vod._resource_available(cfg, force=True)
    assert VodHls._process_alive(os.getpid())
    assert not VodHls._process_alive(0)


def test_metadata_series_batch_shares_fetch_but_explicit_refresh_refetches(state, monkeypatch):
    from pluxy.models import MovieMetadata
    state.cfgm.update({"metadata": {"tmdb_api_key": "synthetic"}})
    first = state.item.model_copy(update={"kind": "series", "series_name": "Series", "series_key": "series"})
    second = first.model_copy(update={"id": "b" * 16})
    calls = []
    def fetch(*args):
        calls.append(args)
        return MovieMetadata(title="Series", matched=True, source="tmdb_tv")
    monkeypatch.setattr(state.metadata, "_fetch_tmdb_tv", fetch)
    state.metadata.get(first, force=True, share_series=True)
    state.metadata.get(second, force=True, share_series=True)
    assert len(calls) == 1
    state.metadata.get(first, force=True)
    assert len(calls) == 2
