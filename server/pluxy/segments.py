"""Local, conservative intro matching and credit-text analysis; no external service."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
import uuid

from PIL import Image, ImageOps

from .probe import NO_WINDOW
from .openings import opening_frame, opening_interval
from .audio_signals import fingerprints as spectral_fingerprints, match as match_audio
from .chapters import read_chapters, skip_chapters
from .credit_text import CreditText, summarize, opening_text_interval
from .preparation import PreparationQueue
from .analysis_budget import AnalysisBudget

RATE = 8000


class AnalysisUnavailable(RuntimeError):
    """An interrupted extraction is never a completed negative detection."""


def fingerprints(raw: bytes) -> list[int]:
    return spectral_fingerprints(raw)


def repeated_intro(left: list[int], right: list[int], step: float = .256, span: float = 1.024):
    found = match_audio(left, right, step=step, span=span)
    return (found.start, found.end) if found else None


def credit_frame(frame: bytes, width: int = 128, height: int = 72) -> bool:
    if len(frame) != width * height:
        return False
    histogram = [0] * 256
    for value in frame:
        histogram[value] += 1
    def percentile(portion):
        total = 0
        for level, count in enumerate(histogram):
            total += count
            if total >= len(frame) * portion:
                return level
        return 255
    background, peak = percentile(.5), percentile(.995)
    if peak - background < 12:
        return False
    threshold = background + max(12, (peak - background) * .65)
    dark = sum(value <= background + (peak - background) * .2 for value in frame) / len(frame)
    bright = sum(value > threshold for value in frame) / len(frame)
    if dark < .68 or not .005 <= bright <= .23:
        return False
    lines = [sum(frame[y * width + x] > threshold and frame[y * width + x + 1] > threshold for x in range(width - 1)) >= 2
             for y in range(height)]
    groups = sum(active and (i == 0 or not lines[i - 1]) for i, active in enumerate(lines))
    # Ordinary subtitles occupy one or two rows near the bottom.
    upper = sum(value > threshold for value in frame[:width * 54])
    return groups >= 4 and upper > sum(value > threshold for value in frame) * .35


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
        self.directory = directory / "segments-v3"
        self.directory.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.pending: set[str] = set()
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="pluxy-segments")
        self.closed = threading.Event()
        self.process = None
        self.ocr_available = None
        self.ocr_cache = {}
        self.playing = 0
        self.playback_until = 0.0
        self.active_media = {}
        self.playback_ready = {}
        self.playback_delay = 12
        self.analysis_for_playback = False
        self.background_item = None
        self.preempted = threading.Event()
        self.budget = AnalysisBudget(self.closed, self._stopped, lambda: self.background_item is not None)
        self.preparation = PreparationQueue(self._ready_at, lambda item: self.request(item, background=True), self._background_busy)

    def _background_busy(self):
        with self.lock:
            return bool(self.closed.is_set() or self.pending or (self.playback_active() and not any(self._active(key) for key in list(self.active_media)))
                        or getattr(self.library, 'is_scanning', False)) or self.budget.resources.constrained()

    def _active(self, item_id):
        count, until = self.active_media.get(item_id, (0, 0))
        return count > 0 or time.time() < until

    def _ready_at(self, item):
        if self.playback_active() and not self._active(item.id):
            return time.time() + 30
        try:
            self.key(item)
            revision = self.revision(item)
        except OSError:
            return None
        cache = self.cached(item)
        if cache and cache.get('peers') == revision:
            if 'segments' in cache and (cache.get('complete', True) or (cache.get('intro_done') and not self._active(item.id))):
                return None
            if cache.get('intro_done') and self.playback_ready.get(item.id, 0) > time.time():
                return self.playback_ready[item.id]
            return cache.get('retry_after', 0)
        return 0

    def prepare(self):
        items = [item for item in self.library.all() if item.kind in {'movie', 'series'}
                 and math.isfinite(item.duration) and item.duration >= 180]
        items.sort(key=lambda item: getattr(item, 'added_at', 0), reverse=True)
        self.preparation.update(items)

    def playback_started(self, item_id=None):
        with self.lock:
            self.playing += 1
            if item_id:
                if not self._active(item_id):
                    self.playback_ready.pop(item_id, None)
                count, _ = self.active_media.get(item_id, (0, 0))
                self.active_media[item_id] = (count + 1, time.time() + 60)
            if self.background_item is not None and self.background_item != item_id:
                self.preempted.set()
                if self.process is not None:
                    try:
                        self.process.kill()
                    except OSError:
                        pass
        if item_id:
            self.playback_touch(item_id)

    def playback_touch(self, item_id):
        with self.lock:
            self.active_media = {key: value for key, value in self.active_media.items() if value[0] > 0 or value[1] > time.time()}
            self.playback_ready = {key: due for key, due in self.playback_ready.items() if key in self.active_media}
            count, _ = self.active_media.get(item_id, (0, 0))
            self.active_media[item_id] = (count, time.time() + 60)
            self.playback_ready.setdefault(item_id, time.time() + self.playback_delay)
        item = self.library.get(item_id)
        if item and item.kind in {'movie', 'series'}:
            self.preparation.prioritize(item)

    def playback_active(self):
        return self.playing > 0 or time.time() < self.playback_until or any(self._active(key) for key in list(self.active_media))

    def _stopped(self):
        return (self.closed.is_set() or self.preempted.is_set()
                or (self.analysis_for_playback and not self._active(self.background_item)))

    def _run_analysis(self, item, background):
        with self.lock:
            self.background_item = item.id if background else None
            self.analysis_for_playback = background and self._active(item.id)
            self.preempted.clear()
            if background and self.playback_active() and not self._active(item.id):
                self.preempted.set()
        try:
            with self.budget.resources.background_thread():
                if self.budget.checkpoint():
                    self.analyze(item, intro_only=not self._active(item.id) or time.time() < self.playback_ready.get(item.id, 0))
        finally:
            with self.lock:
                self.pending.discard(item.id)
                self.background_item = None
                self.analysis_for_playback = False
                self.preempted.clear()
            self.preparation.wake.set()

    def playback_finished(self, item_id=None):
        with self.lock:
            self.playing = max(0, self.playing - 1)
            self.playback_until = time.time() + 60
            if item_id:
                count, _ = self.active_media.get(item_id, (0, 0))
                self.active_media[item_id] = (max(0, count - 1), time.time() + 60)
        self.preparation.wake.set()

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
        value = f"v3:{item.id}:{item.path}:{stat.st_size}:{stat.st_mtime_ns}:{self.cfg.ffmpeg.ffmpeg_path}"
        return hashlib.sha256(value.encode()).hexdigest()

    def cached(self, item) -> dict | None:
        try:
            path = self.directory / (self.key(item) + ".json")
            if path.stat().st_size > 262144:
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

    def request(self, item, *, background=False) -> list[dict]:
        if not background:
            with self.lock:
                if self.background_item == item.id:
                    self.preempted.clear()
        try:
            self.key(item)
            revision = self.revision(item)
        except OSError:
            return []
        cached = self.cached(item)
        known = cached.get("segments", []) if cached and cached.get("peers") == revision else []
        if cached is not None and "segments" in cached and cached.get("peers") == revision and (
                cached.get("complete", True) or (cached.get('intro_done') and not self._active(item.id))):
            return known
        if cached and cached.get('peers') == revision and cached.get("retry_after", 0) > time.time():
            return known
        if self.playback_active() and not self._active(item.id):
            return known
        if cached and cached.get('intro_done') and self._active(item.id) and time.time() < self.playback_ready.get(item.id, 0):
            return known
        if item.kind not in {"movie", "series", "clip", "video"} or not math.isfinite(item.duration) or item.duration < 180 or self.closed.is_set():
            return known
        with self.lock:
            if not self.closed.is_set() and item.id not in self.pending and len(self.pending) < 4:
                if not background and self.background_item is not None:
                    self.preempted.set()
                    if self.process is not None:
                        try:
                            self.process.kill()
                        except OSError:
                            pass
                self.pending.add(item.id)
                self.pool.submit(self._run_analysis, item, background)
        return known

    def extract(self, item, start: float, seconds: float, video: bool = False, dense: bool = False) -> bytes:
        if not self.budget.checkpoint():
            raise AnalysisUnavailable('Preparation interrupted')
        args = [self.cfg.ffmpeg.ffmpeg_path, "-v", "error", "-threads", "1", "-filter_threads", "1"]
        if video and not dense:
            args += ["-skip_frame", "nokey"]
        args += ["-ss", str(start), "-i", item.path,
                "-t", str(seconds)]
        args += (["-an", "-sn", "-vf", "fps=fps=1/2:start_time=0,scale=128:72", "-pix_fmt", "gray", "-f", "rawvideo"] if video else
                 ["-vn", "-sn", "-map", "0:a:0", "-af", "aresample=8000:async=1:first_pts=0", "-ac", "1", "-ar", "8000", "-f", "s16le"])
        args += ["pipe:1"]
        priority = getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0)
        with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, creationflags=NO_WINDOW | priority) as process:
            self.budget.resources.limit_child(process)
            started = time.monotonic()
            with self.lock:
                self.process = process
                if self._stopped():
                    process.kill()
            limit = int(seconds / 2 + 2) * 9216 if video else int(seconds + 2) * RATE * 2
            deadline = time.monotonic() + (30 if video else 180)
            # communicate has a timeout, caps output through the bounded requested window.
            try:
                raw, _ = process.communicate(timeout=max(.1, deadline - time.monotonic()))
                if process.returncode or not raw or len(raw) > limit or self._stopped():
                    raise AnalysisUnavailable("Extraction unavailable")
                return raw
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
                raise AnalysisUnavailable("Extraction timed out") from None
            finally:
                self.budget.rest_after(time.monotonic() - started)
                with self.lock:
                    self.process = None

    def audio(self, item) -> list[int]:
        cached = self.cached(item)
        if cached and isinstance(cached.get("audio"), list) and cached["audio"]:
            return cached["audio"]
        key = self.key(item)
        values = spectral_fingerprints(self.extract(item, 0, min(900, item.duration * .5)), checkpoint=self._checkpoint)
        if self.closed.is_set() or self.key(item) != key:
            return []
        self.write(item, {**(cached or {}), "audio": values})
        return values

    def frame(self, item, seconds: float) -> bytes:
        raw = self.command([self.cfg.ffmpeg.ffmpeg_path, '-v', 'error', '-threads', '1', '-filter_threads', '1', '-ss', str(max(0, seconds)),
                            '-i', item.path, '-an', '-sn', '-frames:v', '1', '-vf', 'scale=128:72',
                            '-pix_fmt', 'gray', '-f', 'rawvideo', 'pipe:1'], 12, 9216)
        if len(raw) != 9216:
            raise AnalysisUnavailable('Image unavailable')
        return raw

    @staticmethod
    def same_picture(left: bytes, right: bytes) -> bool:
        if len(left) != 9216 or len(right) != 9216:
            return False
        # Compare the picture rather than episode-specific names overlaid on it.
        def descriptor(frame):
            cells = [sum(frame[(8 + y * 7 + dy) * 128 + 8 + x * 7 + dx] for dy in range(7) for dx in range(7)) / 49
                     for y in range(8) for x in range(16)]
            mean = sum(cells) / len(cells)
            deviation = math.sqrt(sum((value - mean) ** 2 for value in cells) / len(cells))
            bits = sum(1 << (y * 15 + x) for y in range(8) for x in range(15) if cells[y * 16 + x + 1] > cells[y * 16 + x])
            return cells, deviation, bits
        a, da, ha = descriptor(left)
        b, db, hb = descriptor(right)
        return min(da, db) >= 6 and (ha ^ hb).bit_count() <= 18 and sum(abs(x - y) for x, y in zip(a, b)) / len(a) <= 30

    def visual_intro(self, item, peer, candidate) -> tuple[float, float] | None:
        length = candidate.end - candidate.start
        points = [candidate.start + 1 + (length - 3) * index / 6 for index in range(7)]
        difference = candidate.other_start - candidate.start
        valid = [self.same_picture(self.frame(item, point), self.frame(peer, point + difference)) for point in points]
        # Episode-specific names can cover the same dark opening image. Require
        # three common pictures before accepting any credit text as extra evidence.
        original = valid[:]
        for index in range(len(points)) if sum(original) >= 3 else []:
            if original[index]:
                continue
            try:
                own = self.text_frame(item, points[index])
                other = self.text_frame(peer, points[index] + difference)
                valid[index] = bool(own and other and (own.name_card or own.roles) and (other.name_card or other.roles))
            except (AnalysisUnavailable, OSError, ValueError):
                pass
        runs = []
        first = None
        for index, flag in enumerate(valid + [False]):
            if flag and first is None:
                first = index
            elif not flag and first is not None:
                runs.append((first, index - 1))
                first = None
        if not runs:
            return None
        first, last = max(runs, key=lambda value: value[1] - value[0])
        if last - first < 2 or points[last] - points[first] < 8:
            return None
        start, end = points[first], points[last]
        # Refine the last common image so shared music cannot skip the next scene.
        if last < len(points) - 1:
            high = points[last + 1]
            for _ in range(4):
                point = (end + high) / 2
                if self.same_picture(self.frame(item, point), self.frame(peer, point + difference)):
                    end = point
                else:
                    high = point
        else:
            end = candidate.end
        return start, end

    def keyframe(self, item, seconds: float) -> tuple[float, bytes] | None:
        """Seek directly to one indexed keyframe, without reading minutes of remux packets."""
        if not self.budget.checkpoint():
            raise AnalysisUnavailable('Preparation interrupted')
        args = [self.cfg.ffmpeg.ffmpeg_path, '-hide_banner', '-v', 'info', '-threads', '1', '-filter_threads', '1', '-copyts', '-start_at_zero',
                '-ss', str(max(0, seconds)), '-noaccurate_seek', '-skip_frame', 'nokey', '-i', item.path,
                '-map', '0:v:0', '-an', '-sn', '-frames:v', '1', '-vf', 'showinfo,scale=128:72',
                '-pix_fmt', 'gray', '-f', 'rawvideo', 'pipe:1']
        priority = getattr(subprocess, 'BELOW_NORMAL_PRIORITY_CLASS', 0)
        with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=NO_WINDOW | priority) as process:
            self.budget.resources.limit_child(process)
            started = time.monotonic()
            with self.lock:
                self.process = process
                if self._stopped():
                    process.kill()
            try:
                raw, diagnostic = process.communicate(timeout=8)
                timestamp = re.search(rb'\bn:\s*0\b[^\n]*pts_time:([0-9.e+\-]+)', diagnostic)
                if process.returncode or self._stopped():
                    raise AnalysisUnavailable('Keyframe unavailable')
                if not raw and seconds > item.duration - 15:
                    return None
                if len(raw) != 9216 or not timestamp:
                    raise AnalysisUnavailable('Keyframe timestamp unavailable')
                return float(timestamp[1]), raw
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
                raise AnalysisUnavailable('Keyframe timed out') from None
            finally:
                self.budget.rest_after(time.monotonic() - started)
                with self.lock:
                    self.process = None

    def _checkpoint(self):
        if not self.budget.checkpoint():
            raise AnalysisUnavailable('Preparation interrupted')

    def command(self, args, timeout: float, limit: int) -> bytes:
        if not self.budget.checkpoint():
            raise AnalysisUnavailable('Preparation interrupted')
        priority = getattr(subprocess, 'BELOW_NORMAL_PRIORITY_CLASS', 0)
        with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, creationflags=NO_WINDOW | priority) as process:
            self.budget.resources.limit_child(process)
            started = time.monotonic()
            with self.lock:
                self.process = process
                if self._stopped():
                    process.kill()
            try:
                raw, _ = process.communicate(timeout=timeout)
                if process.returncode or len(raw) > limit or self._stopped():
                    raise AnalysisUnavailable('Local text recognition unavailable')
                return raw
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
                raise AnalysisUnavailable('Local text recognition timed out') from None
            finally:
                self.budget.rest_after(time.monotonic() - started)
                with self.lock:
                    self.process = None

    def text_frame(self, item, seconds: float) -> CreditText | None:
        if os.name != 'nt' or self.ocr_available is False or not shutil.which(self.cfg.ffmpeg.ffmpeg_path):
            return None
        cache_key = (item.id, round(seconds, 2))
        if cache_key in self.ocr_cache:
            return self.ocr_cache[cache_key]
        path = self.directory / ('ocr-' + uuid.uuid4().hex + '.png')
        try:
            raw = self.command([self.cfg.ffmpeg.ffmpeg_path, '-v', 'error', '-threads', '1', '-filter_threads', '1', '-ss', str(max(0, seconds)), '-i', item.path,
                '-an', '-sn', '-frames:v', '1', '-vf', 'scale=2560:1440:force_original_aspect_ratio=decrease',
                '-pix_fmt', 'rgb24', '-c:v', 'png', '-f', 'image2pipe', 'pipe:1'], 12, 16 * 1024 * 1024)
            if not raw and seconds > item.duration - 15:
                return CreditText()
            with Image.open(io.BytesIO(raw)) as image:
                ImageOps.autocontrast(image.convert('L'), cutoff=(.5, .1)).convert('RGB').save(path)
            powershell = Path(os.environ.get('SystemRoot', 'C:/Windows')) / 'System32/WindowsPowerShell/v1.0/powershell.exe'
            data = json.loads(self.command([str(powershell), '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                '-File', str(Path(__file__).with_name('ocr_windows.ps1')), '-Path', str(path.resolve())], 12, 2 * 1024 * 1024).decode('utf-8-sig'))
            self.ocr_available = bool(data.get('available'))
            if not self.ocr_available:
                return None
            with Image.open(path) as image:
                thumbnail = image.convert('L').resize((128, 72))
                blank = sum(value > 8 for value in thumbnail.tobytes()) < 10
            # Credits may sit at the very bottom of an animated opening. Dialogue
            # is rejected by the credit policy, rather than by its screen position.
            lines = [line['text'] for line in data.get('lines', [])]
            result = summarize(lines, blank)
            self.ocr_cache[cache_key] = result
            return result
        finally:
            path.unlink(missing_ok=True)

    def text_opening(self, item):
        samples = []
        for seconds in range(0, int(min(300, item.duration * .4)), 12):
            text = self.text_frame(item, seconds)
            if text is None:
                return None
            samples.append((float(seconds), text))
            if len(samples) >= 2 and all(not (row.name_card or row.dense) for _, row in samples[-2:]):
                found = opening_text_interval(samples)
                if found:
                    return found
        return opening_text_interval(samples)

    def text_credits(self, item):
        start = max(item.duration * .65, item.duration - 600)
        seed = None
        for offset in (90, 30, 180, 300, 420):
            seconds = max(start, item.duration - offset)
            text = self.text_frame(item, seconds)
            if text is None:
                return None
            if text.dense:
                seed = seconds
                break
        if seed is None:
            return None
        lower = upper = seed
        dense = 1
        for direction in (-1, 1):
            blanks = 0
            seconds = seed + direction * 8
            while start <= seconds < item.duration - 1:
                text = self.text_frame(item, seconds)
                if text is None:
                    return None
                if text.roles or text.roster or (direction < 0 and text.name_card):
                    lower = min(lower, seconds)
                    upper = max(upper, seconds)
                    dense += int(text.dense)
                    blanks = 0
                elif text.blank and blanks == 0:
                    blanks += 1
                else:
                    break
                seconds += direction * 8
        confirmed = (upper - lower >= 24 and dense >= 2) or (upper - lower >= 16 and dense >= 3)
        return (lower + 1, upper) if confirmed else None

    def coarse_credits(self, item, start: float):
        samples = []
        for seconds in range(0, math.ceil(item.duration - start), 12):
            if self._stopped():
                raise AnalysisUnavailable('Stopped')
            sample = self.keyframe(item, start + seconds)
            if sample and start - 15 <= sample[0] < item.duration:
                if not samples or sample[0] > samples[-1][0] + .1:
                    samples.append(sample)
        if len(samples) < 3:
            raise AnalysisUnavailable('Too few video samples')
        runs = []
        first = last = None
        for timestamp, frame in samples + [(item.duration + 60, b'')]:
            if credit_frame(frame) and (last is None or timestamp - last <= 40):
                first = timestamp if first is None else first
                last = timestamp
            else:
                if last is not None and timestamp - last <= 40:
                    continue
                if first is not None and last - first >= 12:
                    runs.append((first, last))
                first = last = None
        return runs

    def credits(self, item) -> tuple[float, float] | None:
        try:
            return self.visual_credits(item)
        except AnalysisUnavailable:
            # A slow 4K decoder must not prevent the indexed OCR route from working.
            result = self.text_credits(item)
            if self.ocr_available is not True:
                raise
            return result

    def visual_credits(self, item) -> tuple[float, float] | None:
        start = max(item.duration * .65, item.duration - 600)
        candidates = self.coarse_credits(item, start)
        if not candidates:
            return self.text_credits(item)
        coarse_start, coarse_end = candidates[0]
        scan_start = max(start, coarse_start - 12)
        scan_end = min(item.duration, coarse_end + 12)
        # Validate every keyframe in short bounded windows. This catches a scene
        # between two stretches of credits instead of jumping over it.
        raw = bytearray()
        offset = scan_start
        while offset < scan_end:
            seconds = min(120, scan_end - offset)
            block = self.extract(item, offset, min(seconds + 8, item.duration - offset), video=True)
            wanted = math.ceil(seconds / 2) * 9216
            if len(block) < wanted and offset + seconds < item.duration - 8:
                raise AnalysisUnavailable('Incomplete credit samples')
            raw.extend(block[:wanted])
            offset += seconds
        interval = credit_interval([credit_frame(raw[i:i + 9216]) for i in range(0, len(raw), 9216)])
        if not interval:
            return self.text_credits(item)
        lower, upper = scan_start + interval[0], scan_start + interval[1]
        begin = max(start, lower - 8)
        raw = self.extract(item, begin, min(18, item.duration - begin), video=True, dense=True)
        flags = [credit_frame(raw[i:i + 9216]) for i in range(0, len(raw), 9216)]
        first = next((i for i in range(len(flags) - 1) if flags[i] and flags[i + 1]), None)
        if first is None:
            raise AnalysisUnavailable("Credit boundary not confirmed")
        lower = begin + first * 2
        begin = max(lower, upper - 8)
        raw = self.extract(item, begin, min(18, item.duration - begin), video=True, dense=True)
        flags = [credit_frame(raw[i:i + 9216]) for i in range(0, len(raw), 9216)]
        last = None
        for index, flag in enumerate(flags):
            if flag:
                last = index
            elif last is not None and index - last > 1:
                break
        if last is None:
            raise AnalysisUnavailable("Credit boundary not confirmed")
        upper = min(item.duration - .5, begin + last * 2)
        if upper - lower < 12:
            return None
        proof = self.text_frame(item, (lower + upper) / 2)
        if proof is not None and not proof.roles:
            return self.text_credits(item)
        return lower, upper

    def analyze(self, item, *, intro_only=False) -> None:
        data = None
        try:
            if self._stopped():
                return
            key = self.key(item)
            self.ocr_cache.clear()
            revision = self.revision(item)
            previous = self.cached(item) or {}
            data = {**previous, "peers": revision, "complete": False}
            if previous.get("peers") != revision:
                data.update(segments=[], intro_done=False, credits_done=False)
            data.setdefault("segments", [])
            probe_path = getattr(self.cfg.ffmpeg, 'ffprobe_path', None)
            chapters = skip_chapters(read_chapters(probe_path, item.path), item.duration) if probe_path else []
            for segment in chapters:
                phase = 'intro_done' if segment['title'] == 'Opening' else 'credits_done'
                if not data.get(phase):
                    data['segments'].append(segment)
                    data[phase] = True

            def publish():
                if not self.closed.is_set() and self.key(item) == key:
                    self.write(item, data)

            def add(title, interval):
                if interval:
                    data['segments'].append({'start_ms': round(interval[0] * 1000), 'end_ms': round(interval[1] * 1000), 'title': title})

            if not data.get('intro_done') and not self._stopped():
                try:
                    interval = None
                    if item.kind == 'series':
                        peers = self.peers(item)
                        own = self.audio(item) if peers else []
                        data['audio'] = own
                        candidates = []
                        for peer in peers:
                            candidate = match_audio(own, self.audio(peer), checkpoint=self._checkpoint)
                            if candidate:
                                candidates.append((peer, candidate))
                        for peer, candidate in candidates:
                            if candidate.end - candidate.start < 25:
                                agreeing = [other for _, other in candidates if abs(other.start - candidate.start) < 2
                                            and abs(other.end - candidate.end) < 2]
                                if len(agreeing) >= 2:
                                    interval = (max(c.start for c in agreeing) + 1, min(c.end for c in agreeing) - .5)
                            else:
                                interval = self.visual_intro(item, peer, candidate)
                            if interval:
                                break
                    if interval is None and item.kind == 'movie':
                        interval = self.text_opening(item)
                    if interval is None and (item.kind == 'series' or not self.ocr_available):
                        raw = self.extract(item, 0, min(300, item.duration * .4), video=True)
                        interval = opening_interval([opening_frame(raw[i:i + 9216]) for i in range(0, len(raw), 9216)])
                    add('Opening', interval)
                    data['intro_done'] = True
                    publish()
                except (OSError, ValueError, AnalysisUnavailable, subprocess.SubprocessError) as error:
                    if not self.preempted.is_set():
                        data['last_error'] = type(error).__name__
            if not intro_only and not data.get('credits_done') and not self._stopped():
                try:
                    add('End credits', self.credits(item))
                    data['credits_done'] = True
                except (OSError, ValueError, AnalysisUnavailable, subprocess.SubprocessError) as error:
                    if not self.preempted.is_set():
                        data['last_error'] = type(error).__name__
            data['complete'] = bool(data.get('intro_done') and data.get('credits_done'))
            if data['complete']:
                data.pop('last_error', None)
            data['attempts'] = previous.get('attempts', 0) + (0 if self.preempted.is_set() else 1)
            data['retry_after'] = 0 if data['complete'] or (intro_only and data.get('intro_done')) else time.time() + (5 if self.preempted.is_set() else min(900, 15 * 2 ** min(data['attempts'], 6)))
            publish()
        except (OSError, ValueError, OverflowError, subprocess.SubprocessError):
            pass
        finally:
            self.preparation.wake.set()

    def close(self) -> None:
        self.closed.set()
        self.preparation.close()
        with self.lock:
            if self.process is not None:
                self.process.kill()
        self.pool.shutdown(wait=False, cancel_futures=True)
