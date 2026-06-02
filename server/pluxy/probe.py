"""Wrapper ffprobe : analyse un fichier média en MediaInfo typé."""
from __future__ import annotations

import json
import subprocess
from pathlib import Path

from .models import MediaInfo, MediaStream, StreamKind

# Transferts de couleur indiquant du HDR (PQ / HLG).
_HDR_TRANSFERS = {"smpte2084", "arib-std-b67"}


def probe(ffprobe_path: str, file_path: str) -> MediaInfo:
    cmd = [
        ffprobe_path,
        "-v", "quiet",
        "-print_format", "json",
        "-show_format",
        "-show_streams",
        file_path,
    ]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0 or not out.stdout:
        raise RuntimeError(f"ffprobe a échoué pour {file_path}: {out.stderr.strip()}")

    data = json.loads(out.stdout)
    fmt = data.get("format", {})
    streams: list[MediaStream] = []

    for s in data.get("streams", []):
        ctype = s.get("codec_type")
        if ctype == "video":
            kind = StreamKind.VIDEO
        elif ctype == "audio":
            kind = StreamKind.AUDIO
        elif ctype == "subtitle":
            kind = StreamKind.SUBTITLE
        else:
            continue

        transfer = s.get("color_transfer")
        is_hdr = kind == StreamKind.VIDEO and transfer in _HDR_TRANSFERS

        tags = s.get("tags", {}) or {}
        streams.append(
            MediaStream(
                index=s.get("index", 0),
                kind=kind,
                codec=(s.get("codec_name") or "unknown").lower(),
                width=s.get("width"),
                height=s.get("height"),
                bit_rate=_to_int(s.get("bit_rate")),
                pix_fmt=s.get("pix_fmt"),
                color_transfer=transfer,
                color_primaries=s.get("color_primaries"),
                is_hdr=is_hdr,
                channels=s.get("channels"),
                language=tags.get("language"),
                title=tags.get("title"),
            )
        )

    container = Path(file_path).suffix.lstrip(".").lower()
    size = _to_int(fmt.get("size")) or 0
    duration = float(fmt.get("duration", 0) or 0)
    overall = _to_int(fmt.get("bit_rate")) or (
        int(size * 8 / duration) if duration else 0
    )

    return MediaInfo(
        path=file_path,
        container=container,
        duration=duration,
        size=size,
        overall_bitrate=overall,
        streams=streams,
    )


def _to_int(v) -> int | None:
    try:
        return int(v)
    except (TypeError, ValueError):
        return None
