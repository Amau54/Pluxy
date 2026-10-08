from pathlib import Path
from types import SimpleNamespace
import sys
import threading

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pluxy.config import PluxyConfig
from pluxy.models import MediaInfo, PlaybackDecision, PlaybackMode
from pluxy.transcoder import build_direct_stream_cmd


def test_seek_preserves_video_and_audio_policy():
    cfg = PluxyConfig()
    media = MediaInfo(path="film.mkv", container="mkv", duration=100)
    decision = PlaybackDecision(mode=PlaybackMode.DIRECT_STREAM, audio_action="transcode",
                                target_audio_codec="eac3", target_audio_channels=6)
    cmd = build_direct_stream_cmd(media, decision, cfg, start_time=32.125)
    assert cmd[cmd.index("-ss") + 1] == "32.125"
    assert cmd.index("-ss") < cmd.index("-i")
    assert cmd[cmd.index("-c:v") + 1] == "copy"
    assert cmd[cmd.index("-c:a") + 1] == "eac3"
    assert "-vf" not in cmd
    assert cmd[cmd.index("-avoid_negative_ts") + 1] == "disabled"


def test_remux_rejects_byte_seeks_and_invalid_time_before_starting_process(monkeypatch):
    from pluxy.api import routes_stream
    state = SimpleNamespace(library=SimpleNamespace(get=lambda _: SimpleNamespace(path="film.mkv")))
    monkeypatch.setattr(routes_stream, "get_state", lambda _: state)
    app = FastAPI()
    app.include_router(routes_stream.router)
    with TestClient(app) as client:
        response = client.get("/stream/remux/film", headers={"Range": "bytes=500-"})
        assert response.status_code == 416
        assert response.headers["accept-ranges"] == "none"
        assert client.get("/stream/remux/film?start_ms=-1").status_code == 422
        assert client.get("/stream/remux/film?start_ms=604800001").status_code == 422


def test_failed_start_releases_slot_and_raw_fallback_keeps_original_audio(monkeypatch):
    from pluxy.api import routes_stream
    media = MediaInfo(path="film.mkv", container="mkv", duration=100)
    decision = PlaybackDecision(mode=PlaybackMode.DIRECT_STREAM, audio_action="transcode",
                                target_audio_codec="aac", target_audio_channels=2)
    slots = threading.BoundedSemaphore(1)
    state = SimpleNamespace(library=SimpleNamespace(get=lambda _: SimpleNamespace(path="film.mkv")),
                            cfgm=SimpleNamespace(cfg=PluxyConfig()), remux_slots=slots,
                            decision_for=lambda *_: (media, decision))
    monkeypatch.setattr(routes_stream, "get_state", lambda _: state)
    commands = []
    def fail_start(command, **kwargs):
        commands.append(command)
        raise FileNotFoundError()
    monkeypatch.setattr(routes_stream.subprocess, "Popen", fail_start)
    app = FastAPI()
    app.include_router(routes_stream.router)
    with TestClient(app) as client:
        response = client.get("/stream/remux/film?start_ms=10000&copy_audio=true", follow_redirects=False)
        assert response.status_code == 503
        assert commands[0][commands[0].index("-c:a")+1] == "copy"
        assert decision.audio_action == "transcode"
        assert slots.acquire(blocking=False)
        slots.release()
