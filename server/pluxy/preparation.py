"""A single, resumable background queue; foreground work keeps its executor."""
from collections import OrderedDict
import threading
import time


class PreparationQueue:
    def __init__(self, ready_at, submit, busy, clock=time.time, start_worker=True):
        self.ready_at, self.submit, self.busy, self.clock = ready_at, submit, busy, clock
        self.items = OrderedDict()
        self.due = {}
        self.lock = threading.Lock()
        self.wake = threading.Event()
        self.closed = threading.Event()
        self.thread = None
        self.start_worker = start_worker

    def update(self, items):
        with self.lock:
            if self.closed.is_set():
                return
            self.items = OrderedDict((item.id, item) for item in items)
            self.due.clear()
            if self.thread is None and self.start_worker:
                self.thread = threading.Thread(target=self._run, name='pluxy-preparation', daemon=True)
                self.thread.start()
        self.wake.set()

    def tick(self):
        with self.lock:
            items = list(self.items.items())
        for key, item in items:
            if self.closed.is_set() or self.busy():
                return
            with self.lock:
                if self.items.get(key) is not item or self.due.get(key, 0) > self.clock():
                    continue
            try:
                ready = self.ready_at(item)
                if ready is None:
                    with self.lock:
                        if self.items.get(key) is item:
                            self.items.pop(key, None)
                            self.due.pop(key, None)
                    continue
                if ready > self.clock():
                    with self.lock:
                        self.due[key] = ready
                    continue
                if self.busy():
                    return
                self.submit(item)
                return  # Never enqueue an entire library on the playback executor.
            except Exception:
                with self.lock:
                    self.due[key] = self.clock() + 60

    def _run(self):
        while not self.closed.is_set():
            self.wake.wait(30)
            self.wake.clear()
            if not self.closed.is_set():
                self.tick()

    def close(self):
        self.closed.set()
        self.wake.set()
        with self.lock:
            self.items.clear()
            self.due.clear()
