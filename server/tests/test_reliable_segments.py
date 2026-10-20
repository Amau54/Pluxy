from array import array
import math
import random
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from pluxy.audio_signals import AudioMatch, fingerprints, match
from pluxy.chapters import skip_chapters
from pluxy.credit_text import summarize, opening_text_interval
from pluxy.segments import AnalysisUnavailable, SegmentAnalyzer


def test_spectral_contract_is_shared_with_android():
    fixture = json.loads((Path(__file__).parents[2] / 'assets/contracts/audio-spectral.json').read_text())
    raw = array('h', (int(6000 * math.sin(2 * math.pi * (140 + (i // 4000 * 173) % 1460) * i / 8000)
                          + 4000 * math.sin(2 * math.pi * (140 + (i // 4000 * 307) % 1860) * i / 8000))
                      for i in range(fixture['samples'])))
    assert fingerprints(raw.tobytes()) == fixture['hashes']


def melody(seconds=20):
    # Changing harmonic chords, generated locally; no copyrighted audio fixture.
    rng = random.Random(38)
    notes = [(rng.randrange(140, 1600), rng.randrange(140, 2000)) for _ in range(seconds * 2)]
    return array('h', (int(6000 * math.sin(2 * math.pi * notes[i // 4000][0] * i / 8000)
                           + 4000 * math.sin(2 * math.pi * notes[i // 4000][1] * i / 8000))
                       for i in range(seconds * 8000)))


@pytest.mark.parametrize('offset', [0, 400, 800, 2000, 4000])
def test_spectral_matching_survives_fractional_second_shifts(offset):
    raw = melody()
    rng = random.Random(45)
    def padding():
        return [rng.getrandbits(31) for _ in range(150)]
    left = padding() + fingerprints(raw.tobytes()) + padding()
    right = padding() + fingerprints(raw[offset:].tobytes()) + padding()
    found = match(left, right)
    assert found is not None
    assert found.end - found.start >= 15
    assert found.coverage >= .85


def test_ocr_requires_multiple_distinct_credit_roles_not_one_title_or_dialogue():
    cards = [summarize(['PRODUCED BY', 'ALICE MARTIN']), summarize(['MUSIC BY', 'PAUL RIVERA']),
             summarize(['DIRECTED BY', 'JANE MILLER'])]
    assert opening_text_interval(list(zip([12., 24., 36.], cards))) == (13, 36)
    assert opening_text_interval([(12., cards[0]), (24., cards[0]), (36., cards[0])]) is None
    assert opening_text_interval([(12., summarize(['Where is the director?'])),
                                  (24., summarize(['The music is loud.'])),
                                  (36., summarize(['I am a producer.']))]) is None
    assert summarize(['PAUL RIVERA', 'JANE MARTIN'] * 5).roster
    assert summarize(['edited by', 'ALICE MARTIN']).name_card
    assert not summarize(['This is a sentence.'] * 10).roster


def test_start_markers_never_include_the_end_marker_following_scene():
    chapters = [{'title': 'openCreditStart', 'start_ms': 237405, 'end_ms': 339405},
                {'title': 'openCreditEnd', 'start_ms': 339405, 'end_ms': 500000},
                {'title': 'endCreditsStart', 'start_ms': 3000000, 'end_ms': 3050000},
                {'title': 'endCreditsEnd', 'start_ms': 3050000, 'end_ms': 3100000}]
    found = skip_chapters(chapters, 3200)
    assert [(row['start_ms'], row['end_ms']) for row in found] == [(237405, 339405), (3000000, 3050000)]


def test_failed_phase_retries_without_losing_a_valid_segment(tmp_path, monkeypatch):
    source = tmp_path / 'movie.mkv'
    source.write_bytes(b'fixture')
    item = SimpleNamespace(id='movie', path=str(source), kind='movie', duration=7200)
    analyzer = SegmentAnalyzer(SimpleNamespace(ffmpeg=SimpleNamespace(ffmpeg_path='unused')),
                               SimpleNamespace(all=lambda: []), tmp_path)
    try:
        monkeypatch.setattr(analyzer, 'text_opening', lambda _: (13, 36))
        def unavailable(_):
            raise AnalysisUnavailable('Timed out')
        monkeypatch.setattr(analyzer, 'credits', unavailable)
        analyzer.analyze(item)
        cache = analyzer.cached(item)
        assert cache['complete'] is False
        assert cache['intro_done'] is True
        assert cache['retry_after'] > 0
        assert len(analyzer.request(item)) == 1
        # A later successful attempt preserves the first result, without duplicating it.
        monkeypatch.setattr(analyzer, 'credits', lambda _: (6800, 6900))
        analyzer.analyze(item)
        assert analyzer.cached(item)['complete'] is True
        assert len(analyzer.request(item)) == 2
    finally:
        analyzer.close()


def test_slow_video_analysis_can_fall_back_to_local_text(tmp_path, monkeypatch):
    analyzer = SegmentAnalyzer(SimpleNamespace(), SimpleNamespace(), tmp_path)
    try:
        def timeout(_):
            raise AnalysisUnavailable('Slow decoder')
        monkeypatch.setattr(analyzer, 'visual_credits', timeout)
        monkeypatch.setattr(analyzer, 'text_credits', lambda _: (6800, 6900))
        analyzer.ocr_available = True
        assert analyzer.credits(None) == (6800, 6900)
        analyzer.ocr_available = False
        with pytest.raises(AnalysisUnavailable):
            analyzer.credits(None)
    finally:
        analyzer.close()


def test_short_closing_credits_keep_the_following_scene(tmp_path, monkeypatch):
    analyzer = SegmentAnalyzer(SimpleNamespace(), SimpleNamespace(), tmp_path)
    credits = summarize(['CAST AND CREW', 'CAMERA DEPARTMENT', 'ORIGINAL MUSIC', 'FILM EDITING'])
    scene = summarize([])
    try:
        monkeypatch.setattr(analyzer, 'text_frame', lambda _, seconds: credits if 170 <= seconds < 194 else scene)
        assert analyzer.text_credits(SimpleNamespace(duration=210)) == (173, 188)
    finally:
        analyzer.close()


def test_completed_opening_does_not_scan_the_remaining_movie_window(tmp_path, monkeypatch):
    analyzer = SegmentAnalyzer(SimpleNamespace(), SimpleNamespace(), tmp_path)
    cards = {12: summarize(['PRODUCED BY', 'ALICE MARTIN']), 24: summarize(['MUSIC BY', 'PAUL RIVERA']),
             36: summarize(['DIRECTED BY', 'JANE MILLER'])}
    calls = []
    def frame(_, seconds):
        calls.append(seconds)
        return cards.get(seconds, summarize([]))
    try:
        monkeypatch.setattr(analyzer, 'text_frame', frame)
        assert analyzer.text_opening(SimpleNamespace(duration=7200)) == (13, 36)
        assert max(calls) == 60
    finally:
        analyzer.close()


def test_different_episode_names_complete_a_visually_confirmed_intro(tmp_path, monkeypatch):
    analyzer = SegmentAnalyzer(SimpleNamespace(), SimpleNamespace(), tmp_path)
    candidate = AudioMatch(10, 80, 20, 1, .95)
    try:
        monkeypatch.setattr(analyzer, 'frame', lambda _, seconds: str(seconds).encode())
        def picture(left, _):
            seconds = float(left)
            return seconds < 75 and not (32 < seconds < 34 or 55 < seconds < 57)
        monkeypatch.setattr(analyzer, 'same_picture', picture)
        monkeypatch.setattr(analyzer, 'text_frame', lambda *_: summarize([]))
        assert analyzer.visual_intro(None, None, candidate) is None
        monkeypatch.setattr(analyzer, 'text_frame', lambda _, seconds: summarize(['ALICE MARTIN', 'PAUL RIVERA']) if seconds < 85 else summarize([]))
        start, end = analyzer.visual_intro(None, None, candidate)
        assert start == 11
        assert 74 <= end <= 75
    finally:
        analyzer.close()
