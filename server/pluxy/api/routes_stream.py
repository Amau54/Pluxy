"""
API de lecture — décision + livraison des 3 modes (Workflow 1, 2, 3).

Flux côté client :
  1. POST /api/playback/decide  ->  reçoit une PlaybackDecision + stream_url.
  2. Le client ouvre stream_url :
       - Direct Play  : GET /stream/direct/{id}        (HTTP Range, fichier brut)
       - Direct Stream: GET /stream/remux/{id}         (pipe Matroska remux)
       - Transcode    : GET /stream/hls/{id}/index.m3u8 (HLS NVENC segmenté)
  3. Sous-titres externes : GET /stream/subs/{id}/{n}
"""
from __future__ import annotations

import os
import re
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import (
    FileResponse,
    RedirectResponse,
    Response,
    StreamingResponse,
)

from pydantic import BaseModel

from ..decision import decide
from ..models import ClientCapabilities, MediaInfo, PlaybackDecision, PlaybackMode
from ..probe import probe
from ..state import AppState, get_state
from ..tools import NO_WINDOW
from ..transcoder import build_direct_stream_cmd, build_transcode_cmd
import subprocess

router = APIRouter(tags=["stream"])

_RANGE_RE = re.compile(r"bytes=(\d+)-(\d*)")


def _safe_probe(ffprobe_path: str, path: str) -> MediaInfo:
    """Analyse le média ; en cas d'échec (ffprobe absent/illisible), renvoie un
    MediaInfo minimal (conteneur déduit de l'extension) pour permettre le Direct Play."""
    try:
        return probe(ffprobe_path, path)
    except Exception:
        import os as _os
        return MediaInfo(
            path=path,
            container=Path(path).suffix.lstrip(".").lower(),
            size=_os.path.getsize(path) if _os.path.exists(path) else 0,
            duration=0.0,
            overall_bitrate=0,
            streams=[],
        )


# --------------------------------------------------------------------------- #
#  1. Décision de lecture                                                      #
# --------------------------------------------------------------------------- #
@router.post("/api/playback/decide", response_model=PlaybackDecision)
async def playback_decide(request: Request) -> PlaybackDecision:
    st = get_state(request)
    body = await request.json()
    item_id = body.get("item_id")
    caps = ClientCapabilities.model_validate(body.get("capabilities", {}))

    it = st.library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")

    media = _safe_probe(st.cfgm.cfg.ffmpeg.ffprobe_path, it.path)
    dec = decide(media, caps, st.cfgm.cfg)

    # URL relative que le client doit ouvrir selon le mode retenu.
    if dec.mode == PlaybackMode.DIRECT_PLAY:
        dec.stream_url = f"/stream/direct/{item_id}"
    elif dec.mode == PlaybackMode.DIRECT_STREAM:
        dec.stream_url = f"/stream/remux/{item_id}"
    else:
        variant = "compat" if dec.compat else "main"
        # Enregistre le contexte (media + décision avec vraies capacités client)
        # pour le transcodage VOD à la demande, puis donne la playlist VOD.
        st.vod.register(item_id, variant, media, dec)
        dec.stream_url = f"/stream/hls/{item_id}/{variant}/index.m3u8"
    return dec


# --------------------------------------------------------------------------- #
#  Reprise de lecture (watch-state façon Plex)                                 #
# --------------------------------------------------------------------------- #
@router.get("/api/playback/progress/{item_id}")
def get_progress(item_id: str, request: Request) -> dict:
    return get_state(request).watch.get(item_id)


class ProgressBody(BaseModel):
    position_ms: int = 0
    duration_ms: int = 0


@router.post("/api/playback/progress/{item_id}")
def set_progress(item_id: str, body: ProgressBody, request: Request) -> dict:
    # Pydantic valide/convertit -> 422 propre au lieu d'un 500 sur entrée invalide.
    st = get_state(request)
    st.watch.set(item_id, max(0, body.position_ms), max(0, body.duration_ms))
    return st.watch.get(item_id)


# --------------------------------------------------------------------------- #
#  2a. Direct Play — fichier brut avec support HTTP Range (seek instantané)    #
# --------------------------------------------------------------------------- #
@router.get("/stream/direct/{item_id}")
def stream_direct(item_id: str, request: Request):
    st = get_state(request)
    it = st.library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")

    path = it.path
    if not os.path.isfile(path):
        # Index périmé / disque externe débranché : 404 propre (pas un 500).
        raise HTTPException(404, "Fichier introuvable sur le disque")
    file_size = os.path.getsize(path)
    range_header = request.headers.get("range")
    media_type = _mime_for(it.container)

    if range_header is None:
        return FileResponse(path, media_type=media_type)

    m = _RANGE_RE.match(range_header)
    if not m:
        raise HTTPException(416, "Range invalide")
    start = int(m.group(1))
    if start >= file_size:                     # Range hors limites -> 416
        raise HTTPException(416, "Range non satisfiable",
                            headers={"Content-Range": f"bytes */{file_size}"})
    end = int(m.group(2)) if m.group(2) else file_size - 1
    end = min(end, file_size - 1)
    length = end - start + 1

    def iter_file(chunk: int = 1024 * 1024):
        with open(path, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                data = f.read(min(chunk, remaining))
                if not data:
                    break
                remaining -= len(data)
                yield data

    headers = {
        "Content-Range": f"bytes {start}-{end}/{file_size}",
        "Accept-Ranges": "bytes",
        "Content-Length": str(length),
    }
    return StreamingResponse(
        iter_file(), status_code=206, headers=headers, media_type=media_type
    )


# --------------------------------------------------------------------------- #
#  2b. Direct Stream — remux à la volée (Matroska) sur pipe                    #
# --------------------------------------------------------------------------- #
@router.get("/stream/remux/{item_id}")
def stream_remux(item_id: str, request: Request):
    st = get_state(request)
    it = st.library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")

    cfg = st.cfgm.cfg
    media = _safe_probe(cfg.ffmpeg.ffprobe_path, it.path)
    dec = decide(media, ClientCapabilities(), cfg)
    cmd = build_direct_stream_cmd(media, dec, cfg)

    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, creationflags=NO_WINDOW)
    except FileNotFoundError:
        # FFmpeg absent -> repli Direct Play (fichier brut).
        return RedirectResponse(url=f"/stream/direct/{item_id}", status_code=302)

    def pump(chunk: int = 256 * 1024):
        try:
            while True:
                data = proc.stdout.read(chunk)
                if not data:
                    break
                yield data
        finally:
            # Nettoyage complet : terminate -> wait -> kill de secours -> close.
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
            try:
                proc.stdout.close()
            except Exception:
                pass

    return StreamingResponse(pump(), media_type="video/x-matroska")


# --------------------------------------------------------------------------- #
#  2c. Transcode — HLS VOD à la demande (barre = film entier, seek partout)     #
#  variante main = HEVC ; compat = H.264 1080p. Segments mpegts transcodés      #
#  uniquement quand le lecteur les réclame.                                     #
# --------------------------------------------------------------------------- #
def _ensure_vod_ctx(st: "AppState", item_id: str, variant: str):
    """Garantit un contexte VOD (media + décision). Reconstruit après redémarrage."""
    it = st.library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")
    if variant not in ("main", "compat"):
        raise HTTPException(404, "Variante inconnue")
    if st.vod._context(item_id, variant) is None:
        media = _safe_probe(st.cfgm.cfg.ffmpeg.ffprobe_path, it.path)
        # Décision synthétique cohérente avec la variante de l'URL.
        compat = variant == "compat"
        dec = PlaybackDecision(
            mode=PlaybackMode.TRANSCODE, compat=compat,
            video_action="transcode",
            audio_action="transcode" if compat else "copy",  # mpegts porte tout
            tone_map=False,
            target_bitrate_mbps=st.cfgm.cfg.transcoding.max_bitrate_mbps,
            media=media,
        )
        st.vod.register(item_id, variant, media, dec)
    return it


@router.get("/stream/hls/{item_id}/{variant}/index.m3u8")
def hls_playlist(item_id: str, variant: str, request: Request):
    st: AppState = get_state(request)
    it = _ensure_vod_ctx(st, item_id, variant)
    # Durée connue -> playlist VOD complète (barre de lecture = film entier).
    duration = it.duration or 0.0
    if duration <= 0:
        media = _safe_probe(st.cfgm.cfg.ffmpeg.ffprobe_path, it.path)
        duration = media.duration
    return Response(
        content=st.vod.playlist(duration),
        media_type="application/vnd.apple.mpegurl",
        headers={"Cache-Control": "no-cache"},
    )


# Anti path-traversal : seuls seg_NNNNN.ts sont servis.
_SEGMENT_RE = re.compile(r"^seg_(\d{1,7})\.ts$")


@router.get("/stream/hls/{item_id}/{variant}/{segment}")
def hls_segment(item_id: str, variant: str, segment: str, request: Request):
    st = get_state(request)
    m = _SEGMENT_RE.match(segment)
    if not m:
        raise HTTPException(404, "Segment invalide")
    _ensure_vod_ctx(st, item_id, variant)
    # Transcodage À LA DEMANDE de ce segment (et cache).
    path = st.vod.segment(st.cfgm.cfg, item_id, variant, int(m.group(1)))
    if path is None or not path.exists():
        raise HTTPException(503, "Segment indisponible (transcodage)")
    return FileResponse(path, media_type="video/mp2t")


@router.delete("/stream/hls/{item_id}")
def hls_stop(item_id: str, request: Request) -> dict:
    st = get_state(request)
    st.transcoder.stop_prefix(f"{item_id}_")
    st.vod.cleanup_item(item_id)
    return {"stopped": item_id}


# --------------------------------------------------------------------------- #
#  3. Sous-titres externes (.srt...) — injectés dans ExoPlayer (pas de burn-in)#
# --------------------------------------------------------------------------- #
@router.get("/stream/subs/{item_id}/{sub_index}")
def stream_subs(item_id: str, sub_index: int, request: Request):
    st = get_state(request)
    it = st.library.get(item_id)
    # Borne stricte : rejette les index négatifs (sinon external_subs[-1] servi).
    if not it or sub_index < 0 or sub_index >= len(it.external_subs):
        raise HTTPException(404, "Sous-titre introuvable")
    path = it.external_subs[sub_index]
    if not os.path.isfile(path):
        raise HTTPException(404, "Fichier de sous-titres introuvable")
    return FileResponse(path, media_type=_sub_mime(path))


# --------------------------------------------------------------------------- #
#  Helpers                                                                     #
# --------------------------------------------------------------------------- #
def _mime_for(container: str) -> str:
    return {
        "mp4": "video/mp4",
        "m4v": "video/mp4",
        "mkv": "video/x-matroska",
        "webm": "video/webm",
        "ts": "video/mp2t",
        "mov": "video/quicktime",
        "avi": "video/x-msvideo",
    }.get(container.lower(), "application/octet-stream")


def _sub_mime(path: str) -> str:
    ext = Path(path).suffix.lower()
    return {
        ".srt": "application/x-subrip",
        ".vtt": "text/vtt",
        ".ass": "text/x-ssa",
        ".ssa": "text/x-ssa",
    }.get(ext, "text/plain")
