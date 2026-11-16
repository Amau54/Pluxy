import json
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from pluxy import discs


@pytest.fixture(autouse=True)
def clear_cache():
    discs._inspect.cache_clear()
    discs._probe_title.cache_clear()


def result(stderr="", code=0):
    return subprocess.CompletedProcess([], code, json.dumps({
        "streams": [{"codec_type": "video", "codec_name": "hevc"}],
        "format": {"duration": "600"}}), stderr)


def test_discovery_selects_playlist_not_file_and_invalidates_on_change(tmp_path, monkeypatch):
    source = tmp_path / "Film avec espaces.iso"
    source.write_bytes(b"fixture")
    calls = []
    def run(tool, args):
        calls.append(args)
        return result("playlist 00001.mpls (0:10:00)\nplaylist 00005.mpls (1:30:00)\nselected 00005.mpls")
    monkeypatch.setattr(discs, "_run", run)
    disc = discs.inspect_disc("ffprobe", str(source))
    assert disc.default_title == "bd00005"
    assert [t.duration for t in disc.titles] == [600, 5400]
    assert discs.inspect_disc("ffprobe", str(source)) == disc
    assert len(calls) == 1
    source.write_bytes(b"fixture changed")
    discs.inspect_disc("ffprobe", str(source))
    assert len(calls) == 2
    assert calls[0] == ["-i", "bluray:" + str(source)]
    with pytest.raises(discs.DiscError, match="n’existe pas"):
        discs.probe_title("ffprobe", str(source), "bd99999")
    assert len(calls) == 2


def test_protected_disc_does_not_fall_back_to_another_demuxer(tmp_path, monkeypatch):
    source = tmp_path / "protected.iso"
    source.touch()
    calls = []
    def run(*args):
        calls.append(args)
        return result("Media stream encrypted with AACS", 1)
    monkeypatch.setattr(discs, "_run", run)
    with pytest.raises(discs.DiscError, match="protégé"):
        discs.inspect_disc("ffprobe", str(source))
    assert len(calls) == 1


def test_remux_preserves_all_audio_subtitles_and_seek_before_input(tmp_path):
    source = tmp_path / "film.iso"
    cmd = discs.remux_command("ffmpeg", str(source), "bd00005", 600.125)
    assert cmd[cmd.index("-c") + 1] == "copy"
    assert "0:a?" in cmd and "0:s?" in cmd
    assert cmd[cmd.index("-ss") + 1] == "600.125"
    assert cmd.index("-ss") < cmd.index("-i")
    assert "-vf" not in cmd and "-ac" not in cmd and "-b:v" not in cmd
    assert cmd[cmd.index("-playlist") + 1] == "5"
    for invalid in ("../film", "bd-1", "bd00005 -vf scale", "dvd0", "dvd100"):
        with pytest.raises(discs.DiscError):
            discs.input_arguments(str(source), invalid)


def test_raw_iso_cannot_be_downloaded_as_a_playable_video(monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from pluxy.api import routes_stream
    state = SimpleNamespace(library=SimpleNamespace(get=lambda _: SimpleNamespace(container="iso", path="film.iso")))
    monkeypatch.setattr(routes_stream, "get_state", lambda _: state)
    app = FastAPI()
    app.include_router(routes_stream.router)
    with TestClient(app) as client:
        assert client.get("/stream/direct/film").status_code == 409


def test_disc_compatible_audio_keeps_original_video_and_all_tracks(tmp_path):
    cmd = discs.remux_command("ffmpeg", str(tmp_path / "film.iso"), "bd00000", 120,
                              audio_codec="aac", audio_channels=2)
    assert cmd[cmd.index("-c") + 1] == "copy"
    assert cmd[cmd.index("-c:a") + 1] == "aac"
    assert cmd[cmd.index("-ac") + 1] == "2"
    assert "0:a?" in cmd and "-vf" not in cmd


def test_disc_audio_decision_checks_second_track_and_passthrough(monkeypatch):
    import threading
    from pluxy.api import routes_stream
    from pluxy.config import PluxyConfig
    from pluxy.models import MediaInfo, MediaStream, StreamKind, ClientCapabilities
    media = MediaInfo(path="film.iso", container="iso", streams=[
        MediaStream(index=0, kind=StreamKind.VIDEO, codec="hevc", width=3840, height=2160),
        MediaStream(index=1, kind=StreamKind.AUDIO, codec="aac", channels=2),
        MediaStream(index=2, kind=StreamKind.AUDIO, codec="truehd", channels=8)])
    monkeypatch.setattr(routes_stream, "probe", lambda *_: media)
    state = SimpleNamespace(probe_slots=threading.BoundedSemaphore(1), cfgm=SimpleNamespace(cfg=PluxyConfig()))
    item = SimpleNamespace(path="film.iso", disc_title="bd00000")
    _, compatible = routes_stream._disc_decision(state, item,
        ClientCapabilities(supported_audio_codecs=["aac"], max_audio_channels=2))
    assert compatible.audio_action == "transcode" and compatible.target_audio_codec == "aac"
    assert compatible.video_action == "copy"
    _, original = routes_stream._disc_decision(state, item,
        ClientCapabilities(supported_audio_codecs=["aac", "truehd"], max_audio_channels=8))
    assert original.audio_action == "copy"


def test_disc_passthrough_ignores_unselected_unsupported_track_and_video_limits(monkeypatch):
    import threading
    from pluxy.api import routes_stream
    from pluxy.config import PluxyConfig
    from pluxy.models import MediaInfo, MediaStream, StreamKind, ClientCapabilities
    media = MediaInfo(path="film.iso", container="iso", streams=[
        MediaStream(index=0, kind=StreamKind.VIDEO, codec="hevc", width=3840, height=2160),
        MediaStream(index=1, kind=StreamKind.AUDIO, codec="truehd", channels=8),
        MediaStream(index=2, kind=StreamKind.AUDIO, codec="pcm_bluray", channels=2)])
    monkeypatch.setattr(routes_stream, "probe", lambda *_: media)
    state = SimpleNamespace(probe_slots=threading.BoundedSemaphore(1), cfgm=SimpleNamespace(cfg=PluxyConfig()))
    item = SimpleNamespace(path="film.iso", disc_title="bd00000")
    _, decision = routes_stream._disc_decision(state, item,
        ClientCapabilities(audio_passthrough=True, supported_audio_codecs=["truehd"],
                           max_audio_channels=8, supports_hevc=False, max_video_height=1080))
    assert decision.audio_action == "copy"
    assert decision.target_audio_codec is None
    assert decision.video_action == "copy"
