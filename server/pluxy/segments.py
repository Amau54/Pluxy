"""Local, conservative intro matching and credit-text analysis; no external service."""
from __future__ import annotations

from array import array
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import math
from pathlib import Path
import subprocess
import threading
import time

from .probe import NO_WINDOW

RATE = 8000
COEFFICIENTS = tuple(2 * math.cos(2 * math.pi * (90 * (3000 / 90) ** (i / 31)) / RATE) for i in range(32))


def fingerprints(raw: bytes) -> list[int]:
    samples = array("h", raw[:len(raw) - len(raw) % 2])
    result = []
    for offset in range(0, len(samples) - RATE + 1, RATE):
        frame = samples[offset:offset + RATE]
        if sum(value * value for value in frame) / RATE < 36:
            result.append(-1)
            continue
        energies = [0.0] * 32
        for phase in (0, 2000, 4000, 6000):
            packet = frame[phase:phase + 256]
            for index, coefficient in enumerate(COEFFICIENTS):
                previous = older = 0.0
                for sample in packet:
                    current = sample + coefficient * previous - older
                    older, previous = previous, current
                energies[index] += max(0, previous * previous + older * older - coefficient * previous * older)
        floor = max(energies) * .001
        result.append(sum(1 << i for i in range(31) if energies[i + 1] > energies[i] * 1.12 and energies[i + 1] > floor))
    return result


def repeated_intro(left: list[int], right: list[int]) -> tuple[int, int] | None:
    """A sustained, varied match, excluding silence, static tones and duplicate files."""
    previous = [0] * (len(right) + 1)
    best = (0, 0, 0)
    for i, value in enumerate(left):
        current = [0] * (len(right) + 1)
        for j, other in enumerate(right):
            if value >= 0 and other >= 0 and (value ^ other).bit_count() <= 5:
                current[j + 1] = previous[j] + 1
                length = current[j + 1]
                if length > best[1]:
                    best = (i + 1 - length, length, j + 1 - length)
        previous = current
    start, length, other_start = best
    if not 25 <= length <= 180 or length >= min(len(left), len(right)) * .65:
        return None
    if len(set(left[start:start + length])) < 12:
        return None
    if start + length >= len(left) - 2 or other_start + length >= len(right) - 2:
        return None
    # Leave the last matching second audible rather than risk including dialogue.
    return start, start + length - 1


def credit_frame(frame: bytes, width: int = 128, height: int = 72) -> bool:
    if len(frame) != width * height:
        return False
    dark = sum(value < 44 for value in frame) / len(frame)
    bright = sum(value > 155 for value in frame) / len(frame)
    if dark < .68 or not .005 <= bright <= .23:
        return False
    lines = [sum(value > 155 for value in frame[y * width:(y + 1) * width]) >= 3 for y in range(height)]
    groups = sum(active and (i == 0 or not lines[i - 1]) for i, active in enumerate(lines))
    # Ordinary subtitles occupy one or two rows near the bottom.
    upper = sum(value > 155 for value in frame[:width * 54])
    return groups >= 4 and upper > sum(value > 155 for value in frame) * .35


def credit_interval(flags: list[bool], step: int = 2) -> tuple[int, int] | None:
    best = None
    start = None
    last = 0
    gaps = 0
    for index, flag in enumerate(flags + [False] * 3):
        if flag:
            if start is None:
                start = index
            last = index
            gaps = 0
        elif start is not None:
            gaps += 1
            if gaps > 1:
                if last - start + 1 >= 12 and sum(flags[start:last + 1]) / (last - start + 1) >= .85:
                    candidate = (start * step, last * step)
                    if best is None or candidate[1] - candidate[0] > best[1] - best[0]:
                        best = candidate
                start = None
    return best


class SegmentAnalyzer:
    def __init__(self, cfg, library, directory: Path):
        self.cfg, self.library = cfg, library
        self.directory = directory / "segments-v1"
        self.directory.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.pending: set[str] = set()
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="pluxy-segments")
        self.closed = threading.Event()
        self.process = None

    def peers(self, item):
        identity = item.series_key or item.series_name
        peers = [peer for peer in self.library.all() if peer.id != item.id and peer.kind == "series"
                 and identity and (peer.series_key or peer.series_name) == identity
                 and peer.source_id == item.source_id and peer.season == item.season and peer.duration >= 180]
        return sorted(peers, key=lambda peer: abs((peer.episode or 0) - (item.episode or 0)))[:2]

    def revision(self, item) -> str:
        keys = []
        for peer in self.peers(item) if item.kind == "series" else []:
            try:
                keys.append(self.key(peer))
            except OSError:
                keys.append(peer.id + ":unavailable")
        return "|".join(keys)

    def key(self, item) -> str:
        stat = Path(item.path).stat()
        value = f"v1:{item.id}:{item.path}:{stat.st_size}:{stat.st_mtime_ns}:{self.cfg.ffmpeg.ffmpeg_path}"
        return hashlib.sha256(value.encode()).hexdigest()

    def cached(self, item) -> dict | None:
        try:
            path = self.directory / (self.key(item) + ".json")
            if path.stat().st_size > 32768:
                return None
            value = json.loads(path.read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else None
        except (OSError, ValueError):
            return None

    def write(self, item, data: dict) -> None:
        path = self.directory / (self.key(item) + ".json")
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")
        temporary.replace(path)

    def request(self, item) -> list[dict]:
        try:
            self.key(item)
            revision = self.revision(item)
        except OSError:
            return []
        cached = self.cached(item)
        if cached is not None and "segments" in cached and cached.get("peers") == revision:
            return cached["segments"]
        if item.kind not in {"movie", "series", "clip"} or not math.isfinite(item.duration) or item.duration < 180 or self.closed.is_set():
            return []
        with self.lock:
            if item.id not in self.pending and len(self.pending) < 4:
                self.pending.add(item.id)
                self.pool.submit(self.analyze, item)
        return []

    def extract(self, item, start: float, seconds: float, video: bool = False) -> bytes:
        args = [self.cfg.ffmpeg.ffmpeg_path, "-v", "error", "-threads", "1", "-ss", str(start), "-i", item.path,
                "-t", str(seconds)]
        args += (["-an", "-vf", "fps=1/2,scale=128:72", "-pix_fmt", "gray", "-f", "rawvideo"] if video else
                 ["-vn", "-ac", "1", "-ar", "8000", "-f", "s16le"])
        args += ["pipe:1"]
        priority = getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0)
        with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, creationflags=NO_WINDOW | priority) as process:
            with self.lock:
                self.process = process
                if self.closed.is_set():
                    process.kill()
            limit = int(seconds / 2 + 2) * 9216 if video else int(seconds + 2) * RATE * 2
            deadline = time.monotonic() + 75
            # communicate has a timeout, caps output through the bounded requested window.
            try:
                raw, _ = process.communicate(timeout=max(.1, deadline - time.monotonic()))
                if process.returncode or len(raw) > limit or self.closed.is_set():
                    return b""
                return raw
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
                return b""
            finally:
                with self.lock:
                    self.process = None

    def audio(self, item) -> list[int]:
        cached = self.cached(item)
        if cached and isinstance(cached.get("audio"), list) and cached["audio"]:
            return cached["audio"]
        key = self.key(item)
        values = fingerprints(self.extract(item, 0, min(600, item.duration * .5)))
        if self.closed.is_set() or self.key(item) != key:
            return []
        self.write(item, {"audio": values})
        return values

    def analyze(self, item) -> None:
        try:
            key = self.key(item)
            segments = []
            own = []
            if item.kind == "series":
                peers = self.peers(item)
                if peers:
                    own = self.audio(item)
                    for peer in peers[:2]:
                        if self.closed.is_set():
                            return
                        try:
                            match = repeated_intro(own, self.audio(peer))
                        except OSError:
                            continue
                        if match:
                            segments.append({"start_ms": match[0] * 1000, "end_ms": match[1] * 1000, "title": "Opening"})
                            break
            if not self.closed.is_set():
                start = max(item.duration * .65, item.duration - 600)
                raw = self.extract(item, start, item.duration - start, video=True)
                interval = credit_interval([credit_frame(raw[i:i + 9216]) for i in range(0, len(raw), 9216)])
                if interval:
                    segments.append({"start_ms": int((start + interval[0]) * 1000),
                                     "end_ms": int((start + interval[1]) * 1000), "title": "End credits"})
            if not self.closed.is_set() and self.key(item) == key:
                self.write(item, {"audio": own, "segments": segments, "peers": self.revision(item)})
        except (OSError, ValueError, OverflowError, subprocess.SubprocessError):
            pass
        finally:
            with self.lock:
                self.pending.discard(item.id)

    def close(self) -> None:
        self.closed.set()
        with self.lock:
            if self.process is not None:
                self.process.kill()
        self.pool.shutdown(wait=False, cancel_futures=True)
