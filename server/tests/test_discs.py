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
