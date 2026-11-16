"""Read only the selected Blu-ray playlist, never extract the video from the ISO."""
from functools import lru_cache
from pathlib import Path
import shutil
import subprocess
import threading

from .tools import NO_WINDOW


def parse_playlist(data: bytes) -> tuple[dict[int, str], list[dict]]:
    if len(data) > 1024 * 1024 or data[:4] != b"MPLS":
        return {}, []
    def n(offset, width):
        if offset < 0 or offset + width > len(data):
            raise ValueError("Truncated playlist")
        return int.from_bytes(data[offset:offset + width], "big")
    languages, clips = {}, []
    try:
        playlist, marks = n(8, 4), n(12, 4)
        if playlist + 4 + n(playlist, 4) > len(data) or (marks and marks + 4 + n(marks, 4) > len(data)):
            raise ValueError("Truncated playlist section")
        count = n(playlist + 6, 2)
        if count > 1000:
            return {}, []
        cursor, elapsed = playlist + 10, 0
        for _ in range(count):
            length = n(cursor, 2)
            start, end = cursor + 2, cursor + 2 + length
            if length < 32 or end > len(data):
                raise ValueError("Invalid play item")
            begin, finish = n(start + 12, 4), n(start + 16, 4)
            if finish < begin:
                raise ValueError("Invalid clip timeline")
            clips.append((elapsed, begin))
            elapsed += finish - begin
            table = start + 32
            if n(start + 9, 2) & 0x10:
                angles = n(table, 1)
                table += 2 + max(0, angles - 1) * 10
            table_end = table + 2 + n(table, 2)
            if table_end > end:
                raise ValueError("Invalid stream table")
            streams = n(table + 4, 1) + n(table + 5, 1) + n(table + 6, 1) + n(table + 10, 1)
            pos = table + 16
            for _ in range(streams):
                entry_size = n(pos, 1)
                entry = pos + 1
                attr = entry + entry_size
                attr_size = n(attr, 1)
                if entry_size < 3 or attr_size < 1:
                    raise ValueError("Empty stream entry")
                next_pos = attr + 1 + attr_size
                if next_pos > table_end:
                    raise ValueError("Invalid stream attributes")
                kind, codec = n(entry, 1), n(attr + 1, 1)
                pid = n(entry + 1, 2) if kind == 1 else None
                offset = 3 if codec in {3, 4, 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0xa1, 0xa2, 0x92} else 2
                if attr + offset + 3 > next_pos and codec not in {1, 2, 0xea, 0x1b, 0x24}:
                    raise ValueError("Truncated language")
                if pid is not None and codec in {3, 4, 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0xa1, 0xa2, 0x90, 0x91, 0x92}:
                    lang = data[attr + offset:attr + offset + 3].decode("ascii", errors="ignore")
                    if len(lang) == 3 and lang.isalpha() and lang.islower():
                        languages.setdefault(pid, lang)
                pos = next_pos
            cursor = end
        starts = set()
        if marks:
            count = n(marks + 4, 2)
            if count > 10000:
                raise ValueError("Too many marks")
            for index in range(count):
                pos = marks + 6 + index * 14
                clip = n(pos + 2, 2)
                if n(pos + 1, 1) == 1 and clip < len(clips):
                    base, begin = clips[clip]
                    time = base + n(pos + 4, 4) - begin
                    if 0 <= time < elapsed:
                        starts.add(time)
        ordered = sorted(starts)
        chapters = [{"start_time": start / 45000, "end_time": end / 45000,
                     "tags": {"title": f"Chapitre {index + 1}"}}
                    for index, (start, end) in enumerate(zip(ordered, ordered[1:] + [elapsed]))]
        return languages, chapters
    except (ValueError, IndexError):
        return {}, []


@lru_cache(maxsize=128)
def _read(executable: str, path: str, size: int, modified: int, title: str):
    archive = Path(executable).resolve().parent / "7z.exe"
    command = str(archive) if archive.is_file() else shutil.which("7zz") or shutil.which("7z")
    if not command:
        return {}, []
    proc = subprocess.Popen([command, "e", "-so", "-bd", "-y", path,
                             f"BDMV/PLAYLIST/{title[2:]}.mpls"], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, creationflags=NO_WINDOW)
    timer = threading.Timer(10, proc.kill)
    timer.start()
    try:
        data = proc.stdout.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024:
            proc.kill()
            return {}, []
        if proc.wait(timeout=2) != 0:
            return {}, []
        return parse_playlist(data)
    finally:
        timer.cancel()
        if proc.poll() is None:
            proc.kill()
        proc.wait()
        proc.stdout.close()


def playlist_metadata(executable: str, path: str, title: str):
    if not title.startswith("bd") or len(title) != 7 or not title[2:].isdigit():
        return {}, []
    try:
        stat = Path(path).stat()
        return _read(executable, str(Path(path).resolve()), stat.st_size, stat.st_mtime_ns, title)
    except (OSError, subprocess.SubprocessError):
        return {}, []
