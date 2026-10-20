import asyncio
from types import SimpleNamespace
import threading
import time

from pluxy.preparation import PreparationQueue
from pluxy.segments import AnalysisUnavailable, SegmentAnalyzer
import pytest


def wait_for(predicate):
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.01)
    assert predicate()


def test_queue_respects_cache_retry_playback_and_changes():
    now = [100.0]
    busy = [True]
    states = {'cached': None, 'retry': 150.0, 'new': 0}
    calls = []
    def submit(item):
        calls.append(item.id)
        states[item.id] = None
    queue = PreparationQueue(lambda item: states[item.id], submit, lambda: busy[0], clock=lambda: now[0], start_worker=False)
    try:
        queue.update([SimpleNamespace(id=key) for key in states])
        queue.tick()
        assert calls == []
        busy[0] = False
        queue.tick()
        assert calls == ['new']
        queue.tick()
        assert calls == ['new']
        now[0] = 151
        queue.tick()
        assert calls == ['new', 'retry']
        # A scan replaces the backlog, removing vanished media and keeping new order.
        states['latest'] = 0
        queue.update([SimpleNamespace(id='latest')])
        queue.tick()
        assert calls[-1] == 'latest'
        queue.close()
        queue.update([SimpleNamespace(id='new')])
        queue.tick()
        assert calls == ['new', 'retry', 'latest']
    finally:
        queue.close()


def test_queue_failure_is_delayed_without_blocking_other_media():
    now = [100.0]
    attempts, submitted = [], []
    def ready(item):
        attempts.append(item.id)
        if item.id == 'broken':
            raise OSError('Temporarily disconnected source')
        return None if submitted else 0
    queue = PreparationQueue(ready, lambda item: submitted.append(item.id), lambda: False, clock=lambda: now[0], start_worker=False)
    try:
        queue.update([SimpleNamespace(id='broken'), SimpleNamespace(id='good')])
        queue.tick()
        queue.tick()
        assert submitted == ['good']
        assert attempts.count('broken') == 1
        now[0] = 161
        queue.tick()
        assert attempts.count('broken') == 2
    finally:
        queue.close()


def make_analyzer(tmp_path, count=7):
    items = []
    for index in range(count):
        path = tmp_path / f'movie-{index}.mkv'
        path.write_bytes(b'media fixture')
        items.append(SimpleNamespace(id=str(index), path=str(path), kind='movie', duration=7200, added_at=index))
    library = SimpleNamespace(all=lambda: items, is_scanning=False)
    analyzer = SegmentAnalyzer(SimpleNamespace(ffmpeg=SimpleNamespace(ffmpeg_path='unused')), library, tmp_path)
    return analyzer, items, library


def test_entire_library_is_prepared_before_any_playback_request_and_reused(tmp_path, monkeypatch):
    analyzer, items, library = make_analyzer(tmp_path)
    calls = []
    monkeypatch.setattr(analyzer, 'text_opening', lambda item: calls.append(item.id) or (13, 36))
    monkeypatch.setattr(analyzer, 'credits', lambda _: (6800, 6900))
    try:
        analyzer.prepare()  # No chapter request: seven files must exceed the old four-task limit.
        wait_for(lambda: all((analyzer.cached(item) or {}).get('complete') for item in items))
        assert calls == [str(index) for index in reversed(range(7))]
        assert len(analyzer.request(items[0])) == 2
    finally:
        analyzer.close()
    restored = SegmentAnalyzer(analyzer.cfg, library, tmp_path)
    try:
        monkeypatch.setattr(restored, 'text_opening', lambda _: calls.append('unexpected'))
        restored.prepare()
        wait_for(lambda: not restored.preparation.items)
        assert 'unexpected' not in calls
    finally:
        restored.close()


def test_playback_interrupts_other_preparation_and_keeps_completed_intro(tmp_path, monkeypatch):
    analyzer, items, _ = make_analyzer(tmp_path, 1)
    entered, killed, released = threading.Event(), threading.Event(), threading.Event()
    monkeypatch.setattr(analyzer, 'text_opening', lambda _: (13, 36))
    def credits(_):
        with analyzer.lock:
            analyzer.process = SimpleNamespace(kill=lambda: (killed.set(), released.set()))
        entered.set()
        assert released.wait(3)
        with analyzer.lock:
            analyzer.process = None
        raise AnalysisUnavailable('Interrupted for playback')
    monkeypatch.setattr(analyzer, 'credits', credits)
    try:
        analyzer.prepare()
        assert entered.wait(3)
        analyzer.playback_started()
        assert killed.wait(3)
        wait_for(lambda: not analyzer.pending)
        cache = analyzer.cached(items[0])
        assert cache['intro_done'] and not cache['complete']
        assert cache['attempts'] == 0 and 'last_error' not in cache
        assert len(cache['segments']) == 1
        analyzer.playback_finished()
        assert analyzer._background_busy()  # Grace period also covers HLS request gaps.
        monkeypatch.setattr(analyzer, 'credits', lambda _: (6800, 6900))
        cache['retry_after'] = 0
        analyzer.write(items[0], cache)
        with analyzer.lock:
            analyzer.playback_until = 0
        analyzer.prepare()
        wait_for(lambda: (analyzer.cached(items[0]) or {}).get('complete'))
        assert len(analyzer.request(items[0])) == 2
    finally:
        released.set()
        analyzer.close()


def test_foreground_request_runs_ahead_of_the_remaining_backlog(tmp_path, monkeypatch):
    analyzer, items, _ = make_analyzer(tmp_path, 7)
    entered, release = threading.Event(), threading.Event()
    calls = []
    def opening(item):
        calls.append(item.id)
        if len(calls) == 1:
            entered.set()
            assert release.wait(3)
        return (13, 36)
    monkeypatch.setattr(analyzer, 'text_opening', opening)
    monkeypatch.setattr(analyzer, 'credits', lambda _: (6800, 6900))
    try:
        analyzer.prepare()
        assert entered.wait(3)
        analyzer.request(items[0])
        release.set()
        wait_for(lambda: all((analyzer.cached(item) or {}).get('complete') for item in items))
        assert calls[:2] == ['6', '0']
        assert len(calls) == len(set(calls)) == 7
    finally:
        release.set()
        analyzer.close()


@pytest.mark.parametrize('path', ['/stream/direct/abc', '/stream/remux/abc', '/stream/convert/abc/main', '/stream/hls/abc/main/index.m3u8'])
def test_transfer_activity_is_kept_until_body_end_and_cleared_on_disconnect(path):
    from pluxy.main import PreparationActivityMiddleware
    events = []
    analyzer = SimpleNamespace(playback_started=lambda: events.append('start'), playback_finished=lambda: events.append('end'))
    async def app(scope, receive, send):
        events.append('body')
        assert events == ['start', 'body']
        raise asyncio.CancelledError()
    middleware = PreparationActivityMiddleware(app)
    scope = {'type': 'http', 'method': 'GET', 'path': path,
             'app': SimpleNamespace(state=SimpleNamespace(pluxy=SimpleNamespace(segments=analyzer)))}
    async def run():
        try:
            await middleware(scope, None, None)
        except asyncio.CancelledError:
            pass
    asyncio.run(run())
    assert events == ['start', 'body', 'end']


@pytest.mark.parametrize('playing', [False, True])
def test_periodic_discovery_waits_for_playback(playing):
    from pluxy.state import AppState
    calls = []
    class Closing:
        rounds = 0
        def wait(self, _):
            self.rounds += 1
            return self.rounds > 1
    state = SimpleNamespace(_closing=Closing(), segments=SimpleNamespace(playback_active=lambda: playing),
                            scan_async=lambda: calls.append('scan'))
    AppState._maintain_library(state)
    assert calls == ([] if playing else ['scan'])
