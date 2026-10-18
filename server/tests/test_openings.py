from types import SimpleNamespace
from pathlib import Path
import base64
import json

from pluxy.openings import OpeningFrame, opening_frame, opening_interval
from pluxy.segments import SegmentAnalyzer


def card(top=18, letters=16):
    pixels = bytearray(128 * 72)
    for y in (top, top + 14):
        for x in range(12, 12 + letters * 6, 6):
            for dx, dy in ((0, 0), (0, 1), (0, 2), (0, 3), (1, 3)):
                pixels[(y + dy) * 128 + x + dx] = 230
    return bytes(pixels)


def sequence():
    black = bytes(9216)
    return [black] * 3 + [card()] * 5 + [black] + [card(28, 12)] * 5 + [black] + [card(12, 18)] * 5 + [black] * 3


def test_visual_movie_opening_needs_no_chapter_and_preserves_cold_open():
    frames = [opening_frame(frame) for frame in sequence()]
    assert opening_interval(frames) == (6, 38)
    # End is the last text sample, before any following scene.
    assert opening_interval([OpeningFrame()] * 10 + frames) == (26, 58)


def test_encoded_antialiased_text_cards_remain_recognizable():
    fixture = json.loads((Path(__file__).parents[2] / "assets/contracts/opening-cards.json").read_text())
    cards = [opening_frame(base64.b64decode(frame)) for frame in fixture["frames_base64"]]
    assert all(frame.text for frame in cards)
    black = OpeningFrame(black=True)
    frames = [black] * 4 + [cards[0]] * 5 + [black] + [cards[1]] * 5 + [black] + [cards[2]] * 5 + [black]
    assert opening_interval(frames) == (8, 40)


def test_subtitles_logo_silence_and_dark_action_are_not_openings():
    assert not opening_frame(card(54)).text
    assert not opening_frame(bytes([220]) * 9216).text
    assert not opening_frame(bytes(9216)).text
    assert not opening_frame(b"truncated").black
    rectangle = bytearray(9216)
    for y in range(20, 40):
        rectangle[y * 128 + 40:y * 128 + 80] = bytes([230]) * 40
    assert not opening_frame(bytes(rectangle)).text
    black = OpeningFrame(black=True)
    text = opening_frame(card())
    assert text.text
    assert opening_interval([black] + [text] * 40 + [black]) is None  # one static card
    assert opening_interval([black] * 80) is None
    # Never bridge a narrative scene between otherwise convincing credit cards.
    frames = [opening_frame(frame) for frame in sequence()]
    frames[8] = OpeningFrame()
    assert opening_interval(frames) is None
    # No guessed end at the end of an incomplete sample window.
    assert opening_interval(frames[:8]) is None


def test_movie_analysis_publishes_visual_intro_before_end_credit_scan(tmp_path, monkeypatch):
    path = tmp_path / "film.mkv"
    path.write_bytes(b"fixture")
    item = SimpleNamespace(id="movie", path=str(path), kind="movie", duration=7200)
    analyzer = SegmentAnalyzer(SimpleNamespace(ffmpeg=SimpleNamespace(ffmpeg_path="unused")),
                               SimpleNamespace(all=lambda: []), tmp_path)
    try:
        def extract(_item, start, seconds, video=False):
            assert video
            if start == 0:
                assert seconds == 300
                return b"".join(sequence())
            assert analyzer.cached(item)["complete"] is False
            assert analyzer.request(item) == [{"start_ms": 6000, "end_ms": 38000, "title": "Opening"}]
            return b""
        monkeypatch.setattr(analyzer, "extract", extract)
        analyzer.pending.add(item.id)
        analyzer.analyze(item)
        assert analyzer.cached(item)["complete"] is True
        assert len(analyzer.request(item)) == 1
    finally:
        analyzer.close()
