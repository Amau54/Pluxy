"""Cooperative resource budget for media analysis, independent of playback."""
from contextlib import contextmanager
import ctypes
from ctypes import wintypes
import os
import time


class WindowsResources:
    def __init__(self):
        self.api = ctypes.WinDLL('kernel32', use_last_error=True) if os.name == 'nt' else None
        self.previous = None
        self.next_sample = 0.0
        self.busy = False
        self.paused = False
        if self.api:
            self.api.GetCurrentThread.restype = wintypes.HANDLE
            self.api.SetThreadPriority.argtypes = [wintypes.HANDLE, ctypes.c_int]
            self.api.GetProcessAffinityMask.argtypes = [wintypes.HANDLE, ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.c_size_t)]
            self.api.SetProcessAffinityMask.argtypes = [wintypes.HANDLE, ctypes.c_size_t]

    def constrained(self):
        if not self.api or time.monotonic() < self.next_sample:
            return self.paused
        self.next_sample = time.monotonic() + 2

        class Power(ctypes.Structure):
            _fields_ = [('ac', wintypes.BYTE), ('flags', wintypes.BYTE), ('percent', wintypes.BYTE),
                        ('reserved', wintypes.BYTE), ('remaining', wintypes.DWORD), ('full', wintypes.DWORD)]

        power = Power()
        battery = bool(self.api.GetSystemPowerStatus(ctypes.byref(power)) and power.ac == 0)
        idle, kernel, user = wintypes.FILETIME(), wintypes.FILETIME(), wintypes.FILETIME()
        if self.api.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)):
            values = tuple((row.dwHighDateTime << 32) | row.dwLowDateTime for row in (idle, kernel, user))
            if self.previous:
                di, dk, du = (a - b for a, b in zip(values, self.previous))
                total = dk + du  # Windows kernel time includes idle time.
                if total > 0:
                    load = 1 - di / total
                    self.busy = load >= (.60 if self.busy else .75)
            self.previous = values
        self.paused = battery or self.busy
        return self.paused

    def limit_child(self, process):
        if not self.api:
            return
        allowed, system = ctypes.c_size_t(), ctypes.c_size_t()
        handle = wintypes.HANDLE(int(process._handle))
        if self.api.GetProcessAffinityMask(handle, ctypes.byref(allowed), ctypes.byref(system)) and allowed.value:
            self.api.SetProcessAffinityMask(handle, 1 << (allowed.value.bit_length() - 1))

    @contextmanager
    def background_thread(self):
        handle = self.api.GetCurrentThread() if self.api else None
        changed = bool(self.api and self.api.SetThreadPriority(handle, 0x10000))
        try:
            yield
        finally:
            if changed:
                self.api.SetThreadPriority(handle, 0x20000)


class AnalysisBudget:
    def __init__(self, closed, stopped, background, resources=None):
        self.closed, self.stopped, self.background = closed, stopped, background
        self.resources = resources or WindowsResources()
        self.resume_at = 0.0
        self.cpu = time.thread_time()

    def checkpoint(self):
        now_cpu = time.thread_time()
        cpu_work = max(0, now_cpu - self.cpu)
        if self.background():
            self.resume_at = max(self.resume_at, time.monotonic() + min(2, cpu_work))
        while self.background() and not self.stopped():
            if time.monotonic() >= self.resume_at and not self.resources.constrained():
                break
            self.closed.wait(.25)
        self.cpu = time.thread_time()
        return not self.stopped()

    def rest_after(self, seconds):
        if self.background():
            self.resume_at = time.monotonic() + min(2, max(0, seconds) * .5)
