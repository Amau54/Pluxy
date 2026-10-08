"""Shift sidecar subtitles to the time origin of a remux stream."""
import re

_CUE = re.compile(r"(?m)^(\d+(?::\d{2}){1,2}[.,]\d{3})\s+-->\s+(\d+(?::\d{2}){1,2}[.,]\d{3})(.*)$")


def _milliseconds(value: str) -> int:
    parts = value.replace(",", ".").split(":")
    seconds = float(parts[-1]) + int(parts[-2]) * 60
    if len(parts) == 3:
        seconds += int(parts[0]) * 3600
    return round(seconds * 1000)


def _timestamp(value: int, separator: str = ".", ass: bool = False) -> str:
    value = max(0, value)
    hours, value = divmod(value, 3_600_000)
    minutes, value = divmod(value, 60_000)
    seconds, milliseconds = divmod(value, 1000)
    if ass:
        return f"{hours}:{minutes:02}:{seconds:02}.{milliseconds // 10:02}"
    return f"{hours:02}:{minutes:02}:{seconds:02}{separator}{milliseconds:03}"


def shift_subtitles(text: str, extension: str, offset_ms: int) -> str:
    if extension in {".ass", ".ssa"}:
        output = []
        for line in text.splitlines():
            if line.lower().startswith("dialogue:"):
                fields = line.split(",", 9)
                if len(fields) != 10:
                    continue
                start, end = _milliseconds(fields[1]), _milliseconds(fields[2])
                if end <= offset_ms:
                    continue
                fields[1] = _timestamp(start - offset_ms, ass=True)
                fields[2] = _timestamp(end - offset_ms, ass=True)
                line = ",".join(fields)
            output.append(line)
        return "\n".join(output)
    output = []
    for block in re.split(r"\r?\n\s*\r?\n", text):
        cue = _CUE.search(block)
        if cue:
            start, end = _milliseconds(cue[1]), _milliseconds(cue[2])
            if end <= offset_ms:
                continue
            separator = "," if extension == ".srt" else "."
            timing = f"{_timestamp(start - offset_ms, separator)} --> {_timestamp(end - offset_ms, separator)}{cue[3]}"
            block = block[:cue.start()] + timing + block[cue.end():]
        output.append(block)
    return "\n\n".join(output)
