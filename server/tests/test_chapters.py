from types import SimpleNamespace
import json

from pluxy.chapters import parse_chapters, read_chapters


def test_only_valid_bounded_chapters_are_public():
    assert parse_chapters([]) == []
    assert parse_chapters({"chapters": "bad"}) == []
    assert parse_chapters({"chapters": ["bad"]}) == []
    rows = [{"start_time": "90", "end_time": "120", "tags": {"title": "Intro"}},
            {"start_time": "NaN", "end_time": "130"},
            {"start_time": "-1", "end_time": "10"},
            {"start_time": "10", "end_time": "9"},
            {"start_time": "0", "end_time": "20", "tags": {"title": "x" * 300}}]
    result = parse_chapters({"chapters": rows})
    assert len(result) == 2
    assert result[1] == {"start_ms": 90000, "end_ms": 120000, "title": "Intro"}
    assert len(result[0]["title"]) == 160
    assert len(parse_chapters({"chapters": [rows[0]] * 1000})) == 300


def test_read_chapters_is_cached_and_invalidated_when_file_changes(tmp_path, monkeypatch):
    media = tmp_path / "sample.mkv"
    media.write_bytes(b"test")
    calls = []
    def run(command, **kwargs):
        calls.append(command)
        assert kwargs["timeout"] == 8
        return SimpleNamespace(returncode=0, stdout=json.dumps({"chapters": [{"start_time": "0", "end_time": "30"}]}).encode())
    monkeypatch.setattr("pluxy.chapters.subprocess.run", run)
    assert read_chapters("ffprobe", str(media))[0]["end_ms"] == 30000
    assert read_chapters("ffprobe", str(media))
    assert len(calls) == 1
    media.write_bytes(b"changed")
    assert read_chapters("ffprobe", str(media))
    assert len(calls) == 2


def test_missing_file_or_tool_does_not_prevent_playback(tmp_path, monkeypatch):
    assert read_chapters("missing", str(tmp_path / "absent")) == []
    media = tmp_path / "video"
    media.write_bytes(b"video")
    def missing(*args, **kwargs): raise OSError("tool unavailable")
    monkeypatch.setattr("pluxy.chapters.subprocess.run", missing)
    assert read_chapters("missing", str(media)) == []
