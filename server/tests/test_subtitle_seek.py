from pathlib import Path
import sys
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from pluxy.subtitle_seek import shift_subtitles


def test_srt_clips_active_cue_and_removes_finished_cues():
    text = "1\n00:00:01,000 --> 00:00:02,000\nPast\n\n2\n00:00:08,000 --> 00:00:12,000\nActive\n\n3\n00:00:20,000 --> 00:00:22,000\nLater"
    result = shift_subtitles(text, ".srt", 10000)
    assert "Past" not in result
    assert "00:00:00,000 --> 00:00:02,000\nActive" in result
    assert "00:00:10,000 --> 00:00:12,000\nLater" in result


def test_vtt_preserves_header_cue_identifier_and_settings():
    result = shift_subtitles("WEBVTT\n\nintro\n00:20.000 --> 00:22.000 align:start\nHello", ".vtt", 10000)
    assert result == "WEBVTT\n\nintro\n00:00:10.000 --> 00:00:12.000 align:start\nHello"


def test_ass_preserves_styles_and_commas_in_dialogue():
    text = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:08.00,0:00:12.00,Default,,0,0,0,,Hello, world"
    result = shift_subtitles(text, ".ass", 10000)
    assert "[Events]" in result
    assert "Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,Hello, world" in result


def test_subtitle_route_shifts_time_and_keeps_original_file(tmp_path, monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from pluxy.api import routes_stream
    path = tmp_path / "film.srt"
    text = "1\n00:00:20,000 --> 00:00:22,000\nHello"
    path.write_text(text, encoding="utf-8")
    state = SimpleNamespace(library=SimpleNamespace(get=lambda _: SimpleNamespace(external_subs=[str(path)])))
    monkeypatch.setattr(routes_stream, "get_state", lambda _: state)
    app = FastAPI()
    app.include_router(routes_stream.router)
    with TestClient(app) as client:
        assert "00:00:10,000 --> 00:00:12,000" in client.get("/stream/subs/film/0?start_ms=10000").text
        assert client.get("/stream/subs/film/0").text.replace("\r\n", "\n") == text
    assert path.read_text(encoding="utf-8") == text
