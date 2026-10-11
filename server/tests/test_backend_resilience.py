"""Regressions : scans volumineux, persistance et cycle de vie des transcodages."""
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

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


def make_library(tmp_path, monkeypatch):
    from pluxy.config import PluxyConfig
    from pluxy.library import Library
    from pluxy.models import MediaInfo

    root = tmp_path / "media"
    root.mkdir()
    cfg = PluxyConfig()
    cfg.server.media_dirs = [str(root)]
    calls = []

    def fake_probe(_executable, path):
        calls.append(path)
        return MediaInfo(path=path, container="mkv", duration=float(len(calls)))

    monkeypatch.setattr("pluxy.library.probe", fake_probe)
    return Library(SimpleNamespace(cfg=cfg), tmp_path), root, calls


def test_scan_reuses_unchanged_media_and_reprobes_same_size_replacement(tmp_path, monkeypatch):
    library, root, calls = make_library(tmp_path, monkeypatch)
    video = root / "Film.mkv"
    video.write_bytes(b"aaaa")
    stamp = video.stat().st_mtime_ns

    assert library.scan() == 1
    assert library.scan() == 1
    assert len(calls) == 1
    video.write_bytes(b"bbbb")
    os.utime(video, ns=(stamp + 1_000_000_000, stamp + 1_000_000_000))
    assert library.scan() == 1
    assert len(calls) == 2
    assert library.all()[0].duration == 2


def test_scan_indexes_sidecars_once_and_keeps_subtitle_order(tmp_path, monkeypatch):
    from pluxy.library import Library

    library, root, calls = make_library(tmp_path, monkeypatch)
    for index in range(200):
        (root / f"Film{index:04d}.mkv").write_bytes(b"video")
        (root / f"Film{index:04d}.fr.srt").write_text("fr", encoding="utf-8")
        (root / f"Film{index:04d}.en.srt").write_text("en", encoding="utf-8")
    original = Library._sidecar_subs
    candidate_counts = []

    def counted(media, extensions, candidates=None):
        candidate_counts.append(len(candidates))
        return original(media, extensions, candidates)

    def no_directory_rescan(_path):
        raise AssertionError("Un dossier ne doit pas être réénuméré pour chaque vidéo")

    monkeypatch.setattr(Library, "_sidecar_subs", staticmethod(counted))
    monkeypatch.setattr(Path, "iterdir", no_directory_rescan)
    assert library.scan() == 200
    assert len(calls) == 200
    # 400 candidats au total; un filtre sur tous les sous-titres pour chaque
    # vidéo traiterait 80 000 candidats dans cette même bibliothèque.
    assert sum(candidate_counts) == 400
    assert [track.lang for track in library.all()[0].subtitles] == ["en", "fr"]


def test_scan_preserves_unreadable_subtree_but_removes_confirmed_missing_media(tmp_path, monkeypatch):
    from pluxy import library as library_module

    library, root, _calls = make_library(tmp_path, monkeypatch)
    inaccessible = root / "NAS"
    inaccessible.mkdir()
    (root / "Deleted.mkv").write_bytes(b"video")
    (inaccessible / "Preserved.mkv").write_bytes(b"video")
    assert library.scan() == 2

    def interrupted_walk(_root, onerror):
        yield str(root), ["NAS"], []
        onerror(PermissionError(13, "Partage inaccessible", str(inaccessible)))

    monkeypatch.setattr(library_module.os, "walk", interrupted_walk)
    assert library.scan() == 1
    assert library.all()[0].title == "Preserved"


def test_cancelled_scan_keeps_complete_previous_index(tmp_path, monkeypatch):
    library, root, _calls = make_library(tmp_path, monkeypatch)
    (root / "Film.mkv").write_bytes(b"video")
    assert library.scan() == 1
    persisted = library.index_path.read_bytes()
    stop = threading.Event()
    stop.set()
    (root / "Film.mkv").unlink()
    assert library.scan(stop_event=stop) == 1
    assert len(library.all()) == 1 and not library.is_scanning
    assert library.index_path.read_bytes() == persisted


def test_atomic_writers_use_distinct_temporary_files(tmp_path, monkeypatch):
    from pluxy import paths

    target = tmp_path / "state.json"
    barrier = threading.Barrier(2)
    original = paths.os.replace
    temporary_paths = []
    attempts_lock = threading.Lock()
    first_attempts = set()

    def simultaneous_replace(source, destination):
        with attempts_lock:
            first_attempt = source not in first_attempts
            first_attempts.add(source)
            temporary_paths.append(source)
        if first_attempt:
            barrier.wait(timeout=5)
        original(source, destination)

    monkeypatch.setattr(paths.os, "replace", simultaneous_replace)
    payloads = [json.dumps({"value": value * 1000}) for value in ("A", "B")]
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(paths.atomic_write_text, target, payload) for payload in payloads]
        for future in futures:
            future.result(timeout=10)
    assert len(set(temporary_paths)) == 2
    assert target.read_text(encoding="utf-8") in payloads
    assert not list(tmp_path.glob("*.tmp"))


def test_atomic_write_failure_preserves_original_and_cleans_temporary(tmp_path, monkeypatch):
    from pluxy import paths

    target = tmp_path / "state.json"
    target.write_text("original", encoding="utf-8")

    def disk_failure(*_args):
        raise OSError("Disque en lecture seule")

    monkeypatch.setattr(paths.os, "replace", disk_failure)
    with pytest.raises(OSError):
        paths.atomic_write_text(target, "nouveau")
    assert target.read_text(encoding="utf-8") == "original"
    assert not list(tmp_path.glob("*.tmp"))


@pytest.mark.parametrize("winerror", [5, 32, 33])
def test_atomic_write_retries_temporary_windows_locks_then_succeeds(tmp_path, monkeypatch, winerror):
    from pluxy import paths

    target = tmp_path / "state.json"
    target.write_text("original", encoding="utf-8")
    original_replace = paths.os.replace
    attempts = []
    sleeps = []

    def briefly_locked(source, destination):
        attempts.append(source)
        if len(attempts) <= 2:
            error = PermissionError(13, "Fichier brièvement verrouillé")
            error.winerror = winerror
            raise error
        original_replace(source, destination)

    monkeypatch.setattr(paths.os, "replace", briefly_locked)
    monkeypatch.setattr(paths.time, "sleep", sleeps.append)
    paths.atomic_write_text(target, "nouveau")
    assert len(attempts) == 3 and len(set(attempts)) == 1
    assert sleeps == [0.01, 0.02]
    assert target.read_text(encoding="utf-8") == "nouveau"
    assert not list(tmp_path.glob("*.tmp"))


def test_atomic_write_persistent_windows_lock_is_bounded_and_preserves_target(tmp_path, monkeypatch):
    from pluxy import paths

    target = tmp_path / "state.json"
    target.write_text("original", encoding="utf-8")
    attempts = []
    sleeps = []
    error = PermissionError(13, "Accès définitivement refusé")
    error.winerror = 5

    def permanently_locked(source, _destination):
        attempts.append(source)
        raise error

    monkeypatch.setattr(paths.os, "replace", permanently_locked)
    monkeypatch.setattr(paths.time, "sleep", sleeps.append)
    with pytest.raises(PermissionError) as raised:
        paths.atomic_write_text(target, "nouveau")
    assert raised.value is error
    assert len(attempts) == paths._REPLACE_ATTEMPTS
    assert len(sleeps) == paths._REPLACE_ATTEMPTS - 1
    assert sum(sleeps) < 1.0
    assert target.read_text(encoding="utf-8") == "original"
    assert not list(tmp_path.glob("*.tmp"))


@pytest.mark.parametrize("winerror", [None, 2])
def test_atomic_write_does_not_retry_other_permission_errors(tmp_path, monkeypatch, winerror):
    from pluxy import paths

    target = tmp_path / "state.json"
    target.write_text("original", encoding="utf-8")
    attempts = []
    error = PermissionError(13, "Autre erreur d'autorisation")
    if winerror is not None:
        error.winerror = winerror

    def inaccessible(source, _destination):
        attempts.append(source)
        raise error

    def unexpected_sleep(_delay):
        raise AssertionError("Cette erreur ne doit pas être retentée")

    monkeypatch.setattr(paths.os, "replace", inaccessible)
    monkeypatch.setattr(paths.time, "sleep", unexpected_sleep)
    with pytest.raises(PermissionError) as raised:
        paths.atomic_write_text(target, "nouveau")
    assert raised.value is error and len(attempts) == 1
    assert target.read_text(encoding="utf-8") == "original"
    assert not list(tmp_path.glob("*.tmp"))


def test_config_write_failure_does_not_publish_in_memory_change(tmp_path, monkeypatch):
    from pluxy.config import ConfigManager, PluxyConfig

    default = tmp_path / "default.json"
    cfg = PluxyConfig()
    cfg.server.transcode_temp_dir = str(tmp_path / "cache")
    default.write_text(json.dumps(cfg.model_dump()), encoding="utf-8")
    manager = ConfigManager(tmp_path, default)
    before = manager.path.read_text(encoding="utf-8")

    def disk_failure(*_args):
        raise OSError("Disque plein")

    monkeypatch.setattr("pluxy.paths.atomic_write_text", disk_failure)
    with pytest.raises(OSError):
        manager.update({"server": {"name": "Modification perdue"}})
    assert manager.cfg.server.name == cfg.server.name
    assert manager.path.read_text(encoding="utf-8") == before


@pytest.mark.parametrize("winerror", [None, 5, 32, 33])
def test_config_read_retries_windows_locks_without_loading_defaults(tmp_path, monkeypatch, winerror):
    from pluxy import config

    persisted = tmp_path / "config.json"
    cfg = config.PluxyConfig()
    cfg.server.name = "Réglages utilisateur à conserver"
    cfg.server.transcode_temp_dir = str(tmp_path / "cache")
    persisted.write_text(json.dumps(cfg.model_dump()), encoding="utf-8")
    before = persisted.read_bytes()
    original_read = Path.read_text
    attempts = []
    sleeps = []

    def briefly_locked(path, *args, **kwargs):
        if path == persisted:
            attempts.append(path)
            if len(attempts) <= 2:
                error = PermissionError(13, "Lecture brièvement verrouillée")
                if winerror is not None:
                    error.winerror = winerror
                raise error
        return original_read(path, *args, **kwargs)

    monkeypatch.setattr(config, "os", SimpleNamespace(name="nt", environ={}))
    monkeypatch.setattr(config.time, "sleep", sleeps.append)
    monkeypatch.setattr(Path, "read_text", briefly_locked)
    manager = config.ConfigManager(tmp_path, tmp_path / "missing-default.json")
    assert manager.cfg.server.name == cfg.server.name
    assert len(attempts) == 3 and sleeps == [0.01, 0.02]
    assert persisted.read_bytes() == before
    assert not list(tmp_path.glob("config.invalid-*.json"))


@pytest.mark.parametrize("winerror", [None, 5])
def test_inaccessible_persisted_config_never_replaced_by_defaults(tmp_path, monkeypatch, winerror):
    from pluxy import config

    persisted = tmp_path / "config.json"
    default = tmp_path / "default.json"
    cfg = config.PluxyConfig()
    cfg.server.name = "Réglages personnels"
    cfg.server.transcode_temp_dir = str(tmp_path / "cache")
    persisted.write_text(json.dumps(cfg.model_dump()), encoding="utf-8")
    before = persisted.read_bytes()
    cfg.server.name = "Défauts qui ne doivent jamais être écrits"
    default.write_text(json.dumps(cfg.model_dump()), encoding="utf-8")
    original_read = Path.read_text
    attempts = []
    sleeps = []
    error = PermissionError(13, "Lecture toujours verrouillée")
    if winerror is not None:
        error.winerror = winerror

    def inaccessible(path, *args, **kwargs):
        if path == persisted:
            attempts.append(path)
            raise error
        return original_read(path, *args, **kwargs)

    monkeypatch.setattr(config, "os", SimpleNamespace(name="nt", environ={}))
    monkeypatch.setattr(config.time, "sleep", sleeps.append)
    monkeypatch.setattr(Path, "read_text", inaccessible)
    with pytest.raises(PermissionError) as raised:
        config.ConfigManager(tmp_path, default)
    assert raised.value is error
    assert len(attempts) == config._CONFIG_READ_ATTEMPTS
    assert len(sleeps) == config._CONFIG_READ_ATTEMPTS - 1
    assert persisted.read_bytes() == before
    assert not list(tmp_path.glob("config.invalid-*.json"))
    assert not list(tmp_path.glob("*.tmp"))


def test_config_posix_permission_error_is_not_retried_or_recovered(tmp_path, monkeypatch):
    from pluxy import config

    persisted = tmp_path / "config.json"
    persisted.write_text("{\"server\": {\"name\": \"Personnel\"}}", encoding="utf-8")
    before = persisted.read_bytes()
    original_read = Path.read_text
    attempts = []

    def inaccessible(path, *args, **kwargs):
        if path == persisted:
            attempts.append(path)
            raise PermissionError(13, "Permission POSIX refusée")
        return original_read(path, *args, **kwargs)

    def unexpected_sleep(_delay):
        raise AssertionError("Un accès POSIX refusé ne doit pas être retenté")

    monkeypatch.setattr(config, "os", SimpleNamespace(name="posix"))
    monkeypatch.setattr(config.time, "sleep", unexpected_sleep)
    monkeypatch.setattr(Path, "read_text", inaccessible)
    with pytest.raises(PermissionError):
        config.ConfigManager(tmp_path, tmp_path / "missing-default.json")
    assert len(attempts) == 1 and persisted.read_bytes() == before
    assert not list(tmp_path.glob("config.invalid-*.json"))


@pytest.mark.parametrize("invalid", ["{json cassé", '{"server": {"port": -1}}'])
def test_real_corrupt_config_still_preserved_and_recovered(tmp_path, invalid):
    from pluxy.config import ConfigManager, PluxyConfig

    persisted = tmp_path / "config.json"
    default = tmp_path / "default.json"
    cfg = PluxyConfig()
    cfg.server.name = "Défauts valides"
    cfg.server.transcode_temp_dir = str(tmp_path / "cache")
    default.write_text(json.dumps(cfg.model_dump()), encoding="utf-8")
    persisted.write_text(invalid, encoding="utf-8")
    manager = ConfigManager(tmp_path, default)
    assert manager.cfg.server.name == "Défauts valides"
    backups = list(tmp_path.glob("config.invalid-*.json"))
    assert len(backups) == 1 and backups[0].read_text(encoding="utf-8") == invalid
    assert json.loads(persisted.read_text(encoding="utf-8"))["server"]["name"] == "Défauts valides"


def test_vod_progress_uses_current_playlist_instead_of_previous_seek_cache(tmp_path, monkeypatch):
    from pluxy.vodhls import VodSession

    (tmp_path / "seg_90000.ts").write_bytes(b"ancienne session")
    (tmp_path / "_live.m3u8").write_text(
        "#EXTM3U\n" + "#commentaire\n" * 10_000
        + "#EXTINF:3.0,\nseg_00038.ts\n#EXT-X-ENDLIST\n", encoding="utf-8",
    )

    def no_segment_glob(*_args):
        raise AssertionError("La mesure de progression ne doit pas parcourir tous les segments")

    monkeypatch.setattr(Path, "glob", no_segment_glob)
    session = VodSession(tmp_path, [], 35, lambda: None)
    assert session.produced_max() == 38


def test_vod_cleanup_does_not_replace_a_lock_still_referenced_by_requests(tmp_path):
    from pluxy.config import PluxyConfig
    from pluxy.vodhls import VodHls

    cfg = PluxyConfig()
    cfg.server.transcode_temp_dir = str(tmp_path)
    vod = VodHls(cfg, tmp_path)
    existing_request_lock = vod._lock("film_main")
    vod.cleanup_item("film")
    assert vod._lock("film_main") is existing_request_lock


def test_vod_reaper_rechecks_session_activity_before_stopping(tmp_path, monkeypatch):
    from pluxy.config import PluxyConfig
    from pluxy.vodhls import VodHls

    cfg = PluxyConfig()
    cfg.server.transcode_temp_dir = str(tmp_path)
    vod = VodHls(cfg, tmp_path)
    stopped = []
    session = SimpleNamespace(
        last_access=time.monotonic() - 1000,
        is_alive=lambda: True,
        stop_proc=lambda: stopped.append(True),
    )
    vod._sessions["film_main"] = session
    original = vod._lock

    def refreshed_while_waiting(sid):
        session.last_access = time.monotonic()
        return original(sid)

    monkeypatch.setattr(vod, "_lock", refreshed_while_waiting)
    vod.reap_idle(max_idle=10)
    assert vod._sessions["film_main"] is session
    assert stopped == []


def test_shutdown_stops_transcodes_and_rejects_new_sessions(tmp_path):
    from pluxy.config import PluxyConfig
    from pluxy.models import MediaInfo, PlaybackDecision, PlaybackMode
    from pluxy.transcoder import TranscodeManager
    from pluxy.vodhls import VodHls

    cfg = PluxyConfig()
    cfg.server.transcode_temp_dir = str(tmp_path)
    vod = VodHls(cfg, tmp_path)
    legacy = TranscodeManager(cfg)
    stopped = []
    vod._sessions["film_main"] = SimpleNamespace(stop_proc=lambda: stopped.append("vod"))
    legacy._sessions["film_main"] = SimpleNamespace(stop=lambda: stopped.append("legacy"))
    vod.stop_all()
    legacy.stop_all()
    assert stopped == ["vod", "legacy"]
    assert not vod._sessions and not legacy._sessions
    media = MediaInfo(path="film.mkv", container="mkv")
    vod.register("film", "main", media, PlaybackDecision(mode=PlaybackMode.TRANSCODE))
    assert vod._context("film", "main") is None
    with pytest.raises(RuntimeError, match="arrêt"):
        legacy.get_or_start("film", lambda _directory: [])


def test_fastapi_lifespan_closes_state_on_exception(monkeypatch):
    from pluxy import main

    closed = []
    state = SimpleNamespace(
        cfgm=SimpleNamespace(cfg=SimpleNamespace(server=SimpleNamespace(autostart=False))),
        close=lambda: closed.append(True),
    )
    monkeypatch.setattr(main, "AppState", lambda _base: state)
    monkeypatch.setattr("pluxy.autostart.apply", lambda _enabled: None)

    async def interrupted_lifespan():
        app = SimpleNamespace(state=SimpleNamespace())
        async with main.lifespan(app):
            raise RuntimeError("Interruption")

    with pytest.raises(RuntimeError, match="Interruption"):
        asyncio.run(interrupted_lifespan())
    assert closed == [True]


@pytest.mark.parametrize("compat", [False, True])
def test_software_transcoding_tone_maps_hdr_to_sdr(tmp_path, compat):
    from pluxy.config import PluxyConfig
    from pluxy.models import MediaInfo, MediaStream, PlaybackDecision, PlaybackMode, StreamKind
    from pluxy.transcoder import build_transcode_cmd

    cfg = PluxyConfig()
    cfg.transcoding.hardware_acceleration = False
    cfg.transcoding.tone_map_algorithm = "bt2390"
    media = MediaInfo(
        path="hdr.mkv", container="mkv", streams=[MediaStream(
            index=0, kind=StreamKind.VIDEO, codec="hevc", is_hdr=True,
            pix_fmt="yuv420p10le", width=3840, height=2160,
        )],
    )
    decision = PlaybackDecision(mode=PlaybackMode.TRANSCODE, tone_map=not compat)
    command = build_transcode_cmd(media, decision, cfg, tmp_path, compat=compat)
    chain = command[command.index("-vf") + 1]
    assert "tonemap=tonemap=hable" in chain
    assert "hwdownload" not in chain and "hwupload" not in chain
    assert "-init_hw_device" not in command and "-hwaccel" not in command
    assert command[command.index("-color_trc") + 1] == "bt709"
    assert command[command.index("-c:v") + 1] == ("libx264" if compat else "libx265")


def test_gpu_without_libplacebo_uses_supported_cpu_tonemap_algorithm(tmp_path, monkeypatch):
    from pluxy.config import PluxyConfig
    from pluxy.models import MediaInfo, PlaybackDecision, PlaybackMode
    from pluxy.transcoder import build_transcode_cmd

    cfg = PluxyConfig()
    cfg.transcoding.tone_map_algorithm = "bt2390"
    monkeypatch.setattr("pluxy.transcoder.has_libplacebo", lambda _cfg: False)
    decision = PlaybackDecision(mode=PlaybackMode.TRANSCODE, tone_map=True)
    command = build_transcode_cmd(MediaInfo(path="hdr.mkv", container="mkv"), decision, cfg, tmp_path)
    chain = command[command.index("-vf") + 1]
    assert chain.startswith("hwdownload,format=p010le,")
    assert "tonemap=tonemap=hable" in chain
    assert command[command.index("-color_primaries") + 1] == "bt709"
