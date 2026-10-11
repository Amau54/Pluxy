"""Lecture des chapitres intégrés, sans service de métadonnées externe."""
from collections import OrderedDict
import json
import math
import subprocess
import threading
from pathlib import Path

from .tools import NO_WINDOW

_cache = OrderedDict()
_lock = threading.Lock()
_workers = threading.BoundedSemaphore(2)


def parse_chapters(data):
    result = []
    rows = data.get("chapters", []) if isinstance(data, dict) else []
    for row in (rows if isinstance(rows, list) else [])[:300]:
        if not isinstance(row, dict):
            continue
        try:
            start = float(row["start_time"])
            end = float(row["end_time"])
            tags = row.get("tags")
            title = str(tags.get("title", ""))[:160] if isinstance(tags, dict) else ""
            if not math.isfinite(start) or not math.isfinite(end) or start < 0 or end <= start:
                continue
            result.append({"start_ms": round(start * 1000), "end_ms": round(end * 1000), "title": title})
        except (TypeError, ValueError, KeyError):
            continue
    return sorted(result, key=lambda row: row["start_ms"])


def read_chapters(executable: str, filename: str):
    try:
        stat = Path(filename).stat()
    except OSError:
        return []
    key = (executable, filename, stat.st_mtime_ns, stat.st_size)
    with _lock:
        if key in _cache:
            _cache.move_to_end(key)
            return _cache[key]
    if not _workers.acquire(blocking=False):
        return []
    try:
        output = subprocess.run([executable, "-v", "error", "-show_chapters", "-show_entries",
                                 "chapter=start_time,end_time:chapter_tags=title", "-of", "json", filename],
                                capture_output=True, timeout=8, creationflags=NO_WINDOW)
        if output.returncode or len(output.stdout) > 1024 * 1024:
            return []
        result = parse_chapters(json.loads(output.stdout))
        with _lock:
            _cache[key] = result
            while len(_cache) > 128:
                _cache.popitem(last=False)
        return result
    except (OSError, ValueError, TypeError, subprocess.TimeoutExpired):
        return []
    finally:
        _workers.release()
