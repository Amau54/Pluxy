"""Wrapper ffprobe : analyse un fichier média en MediaInfo typé."""
from __future__ import annotations

import json
import subprocess
from pathlib import Path

from .models import MediaInfo, MediaStream, StreamKind
from .tools import NO_WINDOW

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
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                             timeout=30, creationflags=NO_WINDOW)
    except subprocess.TimeoutExpired:
        # Fichier pathologique / partage réseau lent : on n'immobilise pas un worker.
        raise RuntimeError(f"ffprobe a expiré (timeout) pour {file_path}")
    if out.returncode != 0 or not out.stdout:
        raise RuntimeError(f"ffprobe a échoué pour {file_path}: {(out.stderr or '').strip()}")

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

        master_display = max_cll = None
        if is_hdr:
            # Métadonnées HDR10 statiques (mastering display + content light level).
            # Indispensables pour que la TV bascule en mode HDR sur le flux transcodé.
            master_display, max_cll = _hdr_metadata(ffprobe_path, file_path,
                                                    s.get("index", 0))

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
                master_display=master_display,
                max_cll=max_cll,
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


# HDR10 par défaut (BT.2020, mastering 1000 nits) : repli quand le fichier ne porte
# pas de métadonnées statiques -> la TV bascule quand même en HDR sur le flux transcodé.
_HDR10_FALLBACK_MASTER = "G(8500,39850)B(6550,2300)R(35400,14600)WP(15635,16450)L(10000000,1)"
_HDR10_FALLBACK_MAXCLL = "1000,400"


def _num(v) -> int | None:
    """Numérateur d'une valeur ffprobe « num/den » (déjà à l'échelle FFmpeg)."""
    try:
        return int(str(v).split("/")[0])
    except (TypeError, ValueError, IndexError):
        return None


def _hdr_metadata(ffprobe_path: str, file_path: str, stream_index: int) -> tuple:
    """
    Extrait les métadonnées HDR10 statiques de la 1re frame (mastering display +
    content light level) et les pré-formate pour FFmpeg (`-master_display`/`-max_cll`).
    Best-effort : en cas d'échec, repli sur un HDR10 générique (la TV bascule quand
    même en mode HDR — l'essentiel est la signalisation BT.2020/PQ + le mastering SEI).
    """
    master = maxcll = None
    try:
        cmd = [
            ffprobe_path, "-v", "quiet", "-print_format", "json",
            "-select_streams", f"v:0",
            "-read_intervals", "%+#1",
            "-show_frames", "-show_entries", "frame=side_data_list",
            file_path,
        ]
        out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                             timeout=20, creationflags=NO_WINDOW)
        if out.returncode == 0 and out.stdout:
            for fr in json.loads(out.stdout).get("frames", []):
                for sd in fr.get("side_data_list", []) or []:
                    t = (sd.get("side_data_type") or "").lower()
                    if "mastering display" in t:
                        gx, gy = _num(sd.get("green_x")), _num(sd.get("green_y"))
                        bx, by = _num(sd.get("blue_x")), _num(sd.get("blue_y"))
                        rx, ry = _num(sd.get("red_x")), _num(sd.get("red_y"))
                        wx, wy = _num(sd.get("white_point_x")), _num(sd.get("white_point_y"))
                        lmax, lmin = _num(sd.get("max_luminance")), _num(sd.get("min_luminance"))
                        if None not in (gx, gy, bx, by, rx, ry, wx, wy, lmax, lmin):
                            master = (f"G({gx},{gy})B({bx},{by})R({rx},{ry})"
                                      f"WP({wx},{wy})L({lmax},{lmin})")
                    elif "content light level" in t:
                        mc, ma = _num(sd.get("max_content")), _num(sd.get("max_average"))
                        if mc is not None and ma is not None:
                            maxcll = f"{mc},{ma}"
    except Exception:
        pass
    return (master or _HDR10_FALLBACK_MASTER), (maxcll or _HDR10_FALLBACK_MAXCLL)
