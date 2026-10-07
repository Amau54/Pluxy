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

import asyncio
import math
import os
import re
import subprocess
import threading
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import (
    FileResponse,
    RedirectResponse,
    Response,
    StreamingResponse,
)

from pydantic import BaseModel, Field, ValidationError, model_validator

from ..decision import decide
from ..models import ClientCapabilities, MediaInfo, PlaybackDecision, PlaybackMode
from ..probe import probe
from ..state import AppState, get_state
from ..tools import NO_WINDOW
from ..security import playback_key, session_token
from ..transcoder import build_direct_stream_cmd

router = APIRouter(tags=["stream"])

_RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")
MAX_HLS_SEGMENT_INDEX = 100_000


class _LeasedSegmentResponse(FileResponse):
    def __init__(self, path: Path, vod):
        super().__init__(path, media_type="video/mp2t")
        self.vod = vod
        self.segment_path = path

    async def __call__(self, scope, receive, send):
        try:
            await super().__call__(scope, receive, send)
        finally:
            await asyncio.to_thread(self.vod.release_segment, self.segment_path)


def _parse_range(value: str, file_size: int) -> tuple[int, int]:
    """Une seule plage RFC 7233, ouverte ou suffixe; jamais de longueur negative."""
    match = _RANGE_RE.fullmatch(value.strip())
    if not match or file_size <= 0 or (not match.group(1) and not match.group(2)):
        raise ValueError("Range invalide")
    if not match.group(1):
        suffix = int(match.group(2))
        if suffix <= 0:
            raise ValueError("Range suffixe invalide")
        start = max(0, file_size - suffix)
        return start, file_size - 1
    start = int(match.group(1))
    if start >= file_size:
        raise ValueError("Range hors limites")
    end = int(match.group(2)) if match.group(2) else file_size - 1
    end = min(end, file_size - 1)
    if end < start:
        raise ValueError("Range inverse")
    return start, end


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


def _safe_probe_limited(st: AppState, path: str) -> MediaInfo:
    if not st.probe_slots.acquire(blocking=False):
        raise HTTPException(503, "Trop d'analyses media simultanees")
    try:
        return _safe_probe(st.cfgm.cfg.ffmpeg.ffprobe_path, path)
    finally:
        st.probe_slots.release()


# --------------------------------------------------------------------------- #
#  1. Décision de lecture                                                      #
# --------------------------------------------------------------------------- #
async def _playback_decision(request: Request, register: bool) -> PlaybackDecision:
    st = get_state(request)
    session = playback_key(request)
    try:
        body = await request.json()
    except Exception as exc:
        raise HTTPException(400, "JSON de lecture invalide") from exc
    if not isinstance(body, dict):
        raise HTTPException(400, "Requete de lecture invalide")
    item_id = body.get("item_id")
    if not isinstance(item_id, str) or not item_id or len(item_id) > 64:
        raise HTTPException(422, "Identifiant media invalide")
    try:
        caps = ClientCapabilities.model_validate(body.get("capabilities", {}))
    except ValidationError as exc:
        raise HTTPException(422, "Capacites du lecteur invalides") from exc

    it = st.library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")

    media = await asyncio.to_thread(_safe_probe_limited, st, it.path)
    dec = decide(media, caps, st.cfgm.cfg)
    # Mémorise la décision (capacités client réelles) pour les endpoints de flux.
    if register:
        st.remember_decision(session, item_id, (media, dec))

    # URL relative que le client doit ouvrir selon le mode retenu.
    if dec.mode == PlaybackMode.DIRECT_PLAY:
        dec.stream_url = f"/stream/direct/{item_id}"
    elif dec.mode == PlaybackMode.DIRECT_STREAM:
        dec.stream_url = f"/stream/remux/{item_id}"
    else:
        variant = "compat" if dec.compat else "main"
        # Enregistre le contexte (media + décision avec vraies capacités client)
        # pour le transcodage VOD à la demande, puis donne la playlist VOD.
        if register:
            await asyncio.to_thread(st.vod.register, item_id, variant, media, dec, session, st.cfgm.cfg)
        token = session_token(request)
        dec.stream_url = f"/stream/hls/{item_id}/{variant}/index.m3u8" + (f"?session={token}" if token else "")
    public = dec.model_copy(deep=True)
    if public.media is not None:
        public.media.path = ""
    return public


@router.post("/api/playback/decide", response_model=PlaybackDecision)
async def playback_decide(request: Request) -> PlaybackDecision:
    return await _playback_decision(request, register=True)


@router.post("/api/playback/diagnose", response_model=PlaybackDecision)
async def playback_diagnose(request: Request) -> PlaybackDecision:
    """Même moteur que la lecture, sans créer de session HLS ni état transitoire."""
    return await _playback_decision(request, register=False)


# --------------------------------------------------------------------------- #
#  Reprise de lecture (watch-state façon Plex)                                 #
# --------------------------------------------------------------------------- #
@router.get("/api/playback/progress/{item_id}")
def get_progress(item_id: str, request: Request) -> dict:
    st = get_state(request)
    if not st.library.get(item_id):
        raise HTTPException(404, "Média introuvable")
    return st.watch.get(item_id)


class ProgressBody(BaseModel):
    position_ms: int = Field(default=0, ge=0, le=9_223_372_036_854_775_807)
    duration_ms: int = Field(default=0, ge=0, le=9_223_372_036_854_775_807)
    session_id: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]{8,64}$")
    sequence: int | None = Field(default=None, ge=0, le=9_223_372_036_854_775_807, strict=True)

    @model_validator(mode="after")
    def paired_sequence(self):
        if (self.session_id is None) != (self.sequence is None):
            raise ValueError("Session et sequence doivent etre fournies ensemble")
        return self


@router.post("/api/playback/progress/{item_id}")
def set_progress(item_id: str, body: ProgressBody, request: Request) -> dict:
    # Pydantic valide/convertit -> 422 propre au lieu d'un 500 sur entrée invalide.
    st = get_state(request)
    if not st.library.get(item_id):
        raise HTTPException(404, "Média introuvable")
    try:
        st.watch.set(item_id, body.position_ms, body.duration_ms, body.session_id, body.sequence)
    except OSError as error:
        raise HTTPException(503, "Impossible de sauvegarder la progression") from error
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

    try:
        start, end = _parse_range(range_header, file_size)
    except (ValueError, OverflowError):
        raise HTTPException(
            416,
            "Range non satisfiable",
            headers={"Content-Range": f"bytes */{file_size}"},
        )
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
    if not st.remux_slots.acquire(blocking=False):
        raise HTTPException(503, "Trop de flux remux simultanes")
    # Réutilise la décision calculée avec les VRAIES capacités du client (cible audio
    # correcte) ; repli sur une décision par défaut si elle n'a pas été mémorisée.
    try:
        cached = st.decision_for(playback_key(request), item_id)
        if cached:
            media, dec = cached
        else:
            media = _safe_probe_limited(st, it.path)
            dec = decide(media, ClientCapabilities(), cfg)
        cmd = build_direct_stream_cmd(media, dec, cfg)
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, creationflags=NO_WINDOW)
    except FileNotFoundError:
        st.remux_slots.release()
        # FFmpeg absent -> repli Direct Play (fichier brut).
        return RedirectResponse(url=f"/stream/direct/{item_id}", status_code=302)
    except Exception:
        st.remux_slots.release()
        raise

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
                        proc.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        pass
            try:
                proc.stdout.close()
            except Exception:
                pass
            st.remux_slots.release()

    return StreamingResponse(pump(), media_type="video/x-matroska")


# --------------------------------------------------------------------------- #
#  2c. Transcode — HLS VOD à la demande (barre = film entier, seek partout)     #
#  variante main = HEVC ; compat = H.264 1080p. Segments mpegts transcodés      #
#  uniquement quand le lecteur les réclame.                                     #
# --------------------------------------------------------------------------- #
def _adaptive_main_bitrate(media: MediaInfo, ceiling_mbps: int) -> int:
    """Débit HEVC adaptatif dérivé de la source et toujours réellement réduit."""
    video = media.video
    source_bps = (video.bit_rate if video and video.bit_rate else media.overall_bitrate) or 0
    if source_bps <= 0:
        return max(1, ceiling_mbps)
    source_mbps = max(1, round(source_bps / 1_000_000))
    reduced = max(1, round(source_mbps * 0.70))
    return max(1, min(ceiling_mbps, source_mbps, reduced))


def _adaptive_compat_bitrate(media: MediaInfo, ceiling_mbps: int) -> int:
    """Palier H.264 1080p inférieur au HEVC adaptatif, plafonné à 12 Mbps."""
    video = media.video
    source_bps = (video.bit_rate if video and video.bit_rate else media.overall_bitrate) or 0
    if source_bps <= 0:
        return max(1, min(12, ceiling_mbps))
    source_mbps = max(1, round(source_bps / 1_000_000))
    reduced = max(1, round(source_mbps * 0.55))
    return max(1, min(12, ceiling_mbps, source_mbps, reduced))


def _ensure_vod_ctx(st: "AppState", item_id: str, variant: str, session: str = ""):
    """Garantit un contexte VOD (media + décision). Reconstruit après redémarrage."""
    it = st.library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")
    if variant not in ("main", "compat"):
        raise HTTPException(404, "Variante inconnue")
    if st.vod._context(item_id, variant, session) is None:
        media = _safe_probe_limited(st, it.path)
        # Décision synthétique cohérente avec la variante de l'URL.
        compat = variant == "compat"
        ceiling = st.cfgm.cfg.transcoding.max_bitrate_mbps
        target_bitrate = (
            _adaptive_compat_bitrate(media, ceiling)
            if compat else _adaptive_main_bitrate(media, ceiling)
        )
        dec = PlaybackDecision(
            mode=PlaybackMode.TRANSCODE, compat=compat,
            video_action="transcode",
            audio_action="transcode" if compat else "copy",  # mpegts porte tout
            tone_map=False,
            target_bitrate_mbps=target_bitrate,
            media=media,
        )
        st.vod.register(item_id, variant, media, dec, session, st.cfgm.cfg)
    return it


@router.get("/stream/hls/{item_id}/{variant}/index.m3u8")
def hls_playlist(item_id: str, variant: str, request: Request):
    st: AppState = get_state(request)
    session = playback_key(request)
    it = _ensure_vod_ctx(st, item_id, variant, session)
    # Durée connue -> playlist VOD complète (barre de lecture = film entier).
    duration = it.duration or 0.0
    if duration <= 0:
        media = _safe_probe_limited(st, it.path)
        duration = media.duration
    return Response(
        content=st.vod.playlist(duration, st.vod.segment_time(item_id, variant, session), session_token(request) or ""),
        media_type="application/vnd.apple.mpegurl",
        headers={"Cache-Control": "no-cache"},
    )


# Anti path-traversal : seuls seg_NNNNN.ts sont servis.
_SEGMENT_RE = re.compile(r"^seg_(\d{1,7})\.ts$")


@router.get("/stream/hls/{item_id}/{variant}/{segment}")
async def hls_segment(item_id: str, variant: str, segment: str, request: Request):
    st = get_state(request)
    m = _SEGMENT_RE.match(segment)
    if not m:
        raise HTTPException(404, "Segment invalide")
    if int(m.group(1)) > MAX_HLS_SEGMENT_INDEX:
        raise HTTPException(404, "Segment hors limites")
    session = playback_key(request)
    if not st.hls_request_slots.acquire(blocking=False):
        raise HTTPException(503, "Trop de requetes HLS simultanees", headers={"Retry-After": "1"})
    cancelled = threading.Event()
    def worker():
        item = _ensure_vod_ctx(st, item_id, variant, session)
        segment_index = int(m.group(1))
        duration = float(item.duration or 0.0)
        if segment_index > MAX_HLS_SEGMENT_INDEX or (duration > 0 and segment_index >= max(
                1, math.ceil(duration / st.vod.segment_time(item_id, variant, session)))):
            raise HTTPException(404, "Segment hors de la duree du media")
        return st.vod.get_segment(st.cfgm.cfg, item_id, variant, segment_index, session, cancelled)
    try:
        future = asyncio.get_running_loop().run_in_executor(st.hls_executor, worker)
    except Exception as error:
        # No future owns the slot when submission fails (for example shutdown).
        st.hls_request_slots.release()
        raise HTTPException(503, "Attentes HLS indisponibles", headers={"Retry-After": "1"}) from error
    future.add_done_callback(lambda _future: st.hls_request_slots.release())
    try:
        while not future.done():
            if await request.is_disconnected():
                cancelled.set()
                return Response(status_code=499)
            await asyncio.wait({future}, timeout=0.2)
        path = await asyncio.shield(future)
    finally:
        cancelled.set()
    if path is None or not path.exists():
        raise HTTPException(503, st.vod.cache_error or "Segment indisponible (transcodage)", headers={"Retry-After": "1"})
    if not await asyncio.to_thread(st.vod.pin_segment, path):
        raise HTTPException(404, "Segment expire")
    return _LeasedSegmentResponse(path, st.vod)


@router.delete("/stream/hls/{item_id}")
def hls_stop(item_id: str, request: Request) -> dict:
    st = get_state(request)
    st.vod.cleanup_item(item_id, playback_key(request))
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
        "jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png",
        "webp": "image/webp", "gif": "image/gif", "bmp": "image/bmp",
        "tif": "image/tiff", "tiff": "image/tiff",
        "mp3": "audio/mpeg", "flac": "audio/flac", "m4a": "audio/mp4", "m4b": "audio/mp4",
        "aac": "audio/aac", "ogg": "audio/ogg", "oga": "audio/ogg", "opus": "audio/ogg",
        "wav": "audio/wav", "wma": "audio/x-ms-wma",
    }.get(container.lower(), "application/octet-stream")


def _sub_mime(path: str) -> str:
    ext = Path(path).suffix.lower()
    return {
        ".srt": "application/x-subrip",
        ".vtt": "text/vtt",
        ".ass": "text/x-ssa",
        ".ssa": "text/x-ssa",
    }.get(ext, "text/plain")
