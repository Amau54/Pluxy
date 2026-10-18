from array import array
from types import SimpleNamespace
import random

from pluxy.segments import SegmentAnalyzer, credit_frame, credit_interval, fingerprints, repeated_intro


def signals(seed, length):
    rng = random.Random(seed)
    return [rng.getrandbits(31) for _ in range(length)]


def test_intro_matching_keeps_each_episodes_cold_open():
    intro = signals(99, 40)
    first = signals(1, 30) + intro + signals(2, 80)
    second = signals(3, 12) + intro + signals(4, 100)
    assert repeated_intro(first, second) == (30, 69)
    assert repeated_intro(second, first) == (12, 51)
    noisy = [value ^ (1 << (i % 31)) for i, value in enumerate(intro)]
    assert repeated_intro(first, signals(5, 20) + noisy + signals(6, 80)) == (30, 69)


def test_silence_static_music_duplicate_and_unrelated_episodes_are_rejected():
    assert repeated_intro([-1] * 200, [-1] * 200) is None
    assert repeated_intro([1234] * 200, [1234] * 200) is None
    duplicate = signals(1, 150)
    assert repeated_intro(duplicate, duplicate) is None
    assert repeated_intro(signals(1, 150), signals(2, 150)) is None
    assert repeated_intro(signals(1, 30) + signals(99, 40), signals(2, 12) + signals(99, 40) + signals(3, 100)) is None
    assert fingerprints(array("h", [0] * 16000).tobytes()) == [-1, -1]


def text_frame(rows):
    frame = bytearray(128 * 72)
    for y in rows:
        for x in range(32, 96):
            frame[y * 128 + x] = 230
    return bytes(frame)


def test_credit_analysis_rejects_subtitles_and_preserves_post_credit_scene():
    assert credit_frame(text_frame([12, 19, 26, 33, 40, 47]))
    assert not credit_frame(text_frame([60, 64]))
    assert not credit_frame(bytes([220]) * 9216)
    assert credit_interval([False] * 4 + [True] * 20 + [False] * 8) == (8, 46)
    assert credit_interval([False] * 4 + [True] * 5 + [False] * 8) is None
    assert credit_interval([True, False] * 20) is None


def test_missing_media_and_changed_season_invalidate_cached_analysis(tmp_path):
    source = tmp_path / "episode.mkv"
    source.write_bytes(b"episode")
    cfg = SimpleNamespace(ffmpeg=SimpleNamespace(ffmpeg_path="absent"))
    item = SimpleNamespace(id="one", path=str(source), kind="series", duration=1000, series_key="show", series_name="Show", source_id="disk", season=1, episode=1)
    peer_path = tmp_path / "peer.mkv"
    peer_path.write_bytes(b"peer")
    peer = SimpleNamespace(**{**vars(item), "id": "two", "path": str(peer_path), "episode": 2})
    items = [item]
    analyzer = SegmentAnalyzer(cfg, SimpleNamespace(all=lambda: items), tmp_path)
    try:
        analyzer.write(item, {"segments": [], "audio": [], "peers": analyzer.revision(item)})
        assert analyzer.request(item) == []
        old_revision = analyzer.cached(item)["peers"]
        items.append(peer)
        assert analyzer.revision(item) != old_revision
        source.write_bytes(b"changed episode")
        assert analyzer.cached(item) is None
        source.unlink()
        assert analyzer.request(item) == []
    finally:
        analyzer.close()


def test_intro_is_available_while_credits_are_still_being_analyzed(tmp_path, monkeypatch):
    source = tmp_path / "episode.mkv"
    source.write_bytes(b"episode")
    item = SimpleNamespace(id="one", path=str(source), kind="series", duration=1000)
    analyzer = SegmentAnalyzer(SimpleNamespace(ffmpeg=SimpleNamespace(ffmpeg_path="unused")), SimpleNamespace(all=lambda: []), tmp_path)
    opening = {"start_ms": 30000, "end_ms": 69000, "title": "Opening"}
    try:
        monkeypatch.setattr(analyzer, "revision", lambda _: "season")
        monkeypatch.setattr(analyzer, "peers", lambda _: [item])
        monkeypatch.setattr(analyzer, "audio", lambda _: [1, 2])
        monkeypatch.setattr("pluxy.segments.repeated_intro", lambda *_: (30, 69))
        def credits(*args, **kwargs):
            assert analyzer.cached(item)["complete"] is False
            assert analyzer.request(item) == [opening]
            return b""
        monkeypatch.setattr(analyzer, "extract", credits)
        analyzer.pending.add(item.id)
        analyzer.analyze(item)
        assert analyzer.cached(item)["complete"] is True
        assert analyzer.request(item) == [opening]
    finally:
        analyzer.close()
