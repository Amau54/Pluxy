import ctypes
import os
import subprocess
import sys
import threading
from types import SimpleNamespace
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pluxy.analysis_budget import AnalysisBudget, WindowsResources
from pluxy.audio_signals import fingerprints, match


def test_pressure_pause_can_be_interrupted_and_foreground_never_waits():
    stopped = threading.Event()
    waits = []
    class Closing:
        def wait(self, seconds):
            waits.append(seconds)
            stopped.set()
    budget = AnalysisBudget(Closing(), stopped.is_set, lambda: True,
                            SimpleNamespace(constrained=lambda: True))
    assert budget.checkpoint() is False
    assert waits == [.25]
    stopped.clear()
    budget.background = lambda: False
    assert budget.checkpoint() is True
    assert waits == [.25]


def test_cpu_work_and_decoding_insert_bounded_rest(monkeypatch):
    clock, cpu, waits = [10.0], [0.0], []
    monkeypatch.setattr('pluxy.analysis_budget.time.monotonic', lambda: clock[0])
    monkeypatch.setattr('pluxy.analysis_budget.time.thread_time', lambda: cpu[0])
    class Closing:
        def wait(self, seconds):
            waits.append(seconds)
            clock[0] += seconds
    budget = AnalysisBudget(Closing(), lambda: False, lambda: True,
                            SimpleNamespace(constrained=lambda: False))
    cpu[0] += .5
    assert budget.checkpoint()
    assert sum(waits) == .5
    waits.clear()
    budget.rest_after(30)
    assert budget.checkpoint()
    assert sum(waits) == 2  # Never insert an unbounded sleep after a slow decoder.


def test_battery_sampling_cache_and_cpu_hysteresis(monkeypatch):
    clock = [0.0]
    monkeypatch.setattr('pluxy.analysis_budget.time.monotonic', lambda: clock[0])
    resources = WindowsResources()
    ac, idle, kernel = [0], [0], [0]
    class Api:
        def GetSystemPowerStatus(self, result):
            result._obj.ac = ac[0]
            return 1
        def GetSystemTimes(self, i, k, u):
            i._obj.dwLowDateTime, k._obj.dwLowDateTime = idle[0], kernel[0]
            return 1
    resources.api = Api()
    assert resources.constrained()
    clock[0] = 1
    assert resources.constrained()  # Battery state must survive the sample cache.
    ac[0] = 1
    for timestamp, idle_delta, expected in [(3, 90, False), (6, 10, True), (9, 35, True), (12, 70, False)]:
        clock[0] = timestamp
        idle[0] += idle_delta
        kernel[0] += 100
        assert resources.constrained() is expected


def test_cooperative_audio_checks_keep_identical_results_and_can_abort():
    raw = b'\x12\x04' * 8000
    checks = []
    assert fingerprints(raw, checkpoint=lambda: checks.append(True)) == fingerprints(raw)
    assert checks
    left = [i * 14713 for i in range(160)]
    checks.clear()
    assert match(left, left, checkpoint=lambda: checks.append(True)) == match(left, left)
    assert checks
    def stop():
        raise InterruptedError()
    with pytest.raises(InterruptedError):
        fingerprints(raw, checkpoint=stop)
    with pytest.raises(InterruptedError):
        match(left, left, checkpoint=stop)


@pytest.mark.skipif(os.name != 'nt', reason='Native Windows affinity')
def test_real_child_affinity_does_not_limit_the_server():
    resources = WindowsResources()
    flags = subprocess.CREATE_NO_WINDOW | subprocess.BELOW_NORMAL_PRIORITY_CLASS
    with subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(10)'], creationflags=flags) as child:
        try:
            allowed, system = ctypes.c_size_t(), ctypes.c_size_t()
            server = ctypes.WinDLL('kernel32').GetCurrentProcess
            server.restype = ctypes.c_void_p
            assert resources.api.GetProcessAffinityMask(server(), ctypes.byref(allowed), ctypes.byref(system))
            before = allowed.value
            resources.limit_child(child)
            assert resources.api.GetProcessAffinityMask(int(child._handle), ctypes.byref(allowed), ctypes.byref(system))
            assert allowed.value.bit_count() == 1
            assert resources.api.GetProcessAffinityMask(server(), ctypes.byref(allowed), ctypes.byref(system))
            assert allowed.value == before
        finally:
            child.kill()
