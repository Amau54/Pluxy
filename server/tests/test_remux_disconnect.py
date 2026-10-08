from pathlib import Path
import asyncio
import io
import sys
import threading

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from pluxy.api.routes_stream import _RemuxResponse
from starlette.requests import ClientDisconnect


class Process:
    def __init__(self, pipe=None):
        self.stdout = pipe or io.BytesIO(b"matroska")
        self.stopped = False

    def poll(self):
        return 0 if self.stopped else None

    def terminate(self):
        self.stopped = True

    def wait(self, timeout):
        return 0


def test_socket_failure_before_iteration_releases_each_reserved_slot():
    slots = threading.BoundedSemaphore(1)

    async def scenario():
        async def receive():
            return {"type": "http.disconnect"}

        async def send(message):
            raise OSError("client parti avant le premier octet")

        for _ in range(12):
            assert slots.acquire(blocking=False)
            proc = Process()
            response = _RemuxResponse(proc, slots, 600_000)
            with pytest.raises(ClientDisconnect):
                await response({"type": "http", "asgi": {"spec_version": "2.4"}}, receive, send)
            assert proc.stopped and proc.stdout.closed
        assert slots.acquire(blocking=False)

    asyncio.run(scenario())


def test_disconnect_cancels_blocked_read_and_releases_process():
    entered = threading.Event()
    stopped = threading.Event()

    class Pipe(io.BytesIO):
        def read(self, size):
            entered.set()
            assert stopped.wait(3), "Le processus doit être arrêté sans attendre stdout"
            return b""

    class BlockedProcess(Process):
        def terminate(self):
            super().terminate()
            stopped.set()

    slots = threading.BoundedSemaphore(1)
    assert slots.acquire(blocking=False)
    proc = BlockedProcess(Pipe(b"pending"))

    async def scenario():
        async def receive():
            assert await asyncio.to_thread(entered.wait, 2)
            return {"type": "http.disconnect"}

        async def send(message):
            pass

        await _RemuxResponse(proc, slots, 600_000)(
            {"type": "http", "asgi": {"spec_version": "2.0"}}, receive, send)

    asyncio.run(scenario())
    assert proc.stopped and proc.stdout.closed
    assert slots.acquire(blocking=False)


def test_invalid_windows_process_handle_still_releases_slot():
    class InvalidHandle(Process):
        def poll(self):
            raise OSError(6, "Descripteur non valide")

    slots = threading.BoundedSemaphore(1)
    assert slots.acquire(blocking=False)
    proc = InvalidHandle()
    response = _RemuxResponse(proc, slots, 600_000)
    response._close()
    response._close()
    assert proc.stdout.closed
    assert slots.acquire(blocking=False)
