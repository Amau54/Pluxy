"""API bibliothèque — scan, listing, détail média, métadonnées."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request, Query
from fastapi.responses import FileResponse

from ..models import MediaInfo, MediaItem, MovieMetadata
from ..probe import probe
from ..state import AppState, get_state
from ..security import is_loopback_request, require_local_admin
from ..thumbnails import ThumbnailBusy

router = APIRouter(prefix="/api/library", tags=["library"])


def _enrich(item: MediaItem, st: AppState) -> MediaItem:
    """Copie publique enrichie : poster/année depuis le cache + chemin disque MASQUÉ.

    On ne renvoie jamais le chemin absolu réel au client (fuite d'info sur le
    serveur). On travaille sur une COPIE pour ne pas muter l'objet indexé.
    """
    pub = item.model_copy()
    pub.path = ""                              # ne pas exposer le chemin serveur
    pub.external_subs = []
    meta = st.metadata.cached(item.id)
    if meta:
        pub.year = meta.year
        pub.poster_url = meta.poster_url
        pub.has_metadata = meta.matched
    # Toujours exposer la capture locale : le client peut ainsi la conserver
    # comme solution de repli lorsque l'affiche TMDB est inaccessible hors ligne.
    if pub.kind in {"movie", "series"}:
        pub.thumbnail_url = f"/api/library/items/{item.id}/thumbnail"
    # Fusionne l'état de visionnage PERSISTANT (le client affiche « vu » / reprise).
    w = st.watch.get(item.id)
    pos = int(w.get("position_ms", 0) or 0)
    dur = int(w.get("duration_ms", 0) or 0)
    pub.watched = bool(w.get("watched"))
    pub.in_progress = (not pub.watched) and pos > 0
    pub.progress = (pos / dur) if (pub.in_progress and dur > 0) else 0.0
    pub.last_played_at = float(w.get("last_played_at", 0) or 0)
    return pub


@router.post("/scan")
def scan(request: Request) -> dict:
    """Lance un (re)scan en arrière-plan ; renvoie immédiatement."""
    st = get_state(request)
    started = st.scan_async()
    return {
        "status": "scanning" if started else "already_scanning",
        "current_count": len(st.library.all()),
    }


@router.get("/scan-status")
def scan_status(request: Request) -> dict:
    st = get_state(request)
    return {"scanning": st.library.is_scanning, "count": len(st.library.all()), **st.library.scan_status,
            "metadata": getattr(st, "enrich_status", {"status": "idle"})}


@router.get("/items", response_model=list[MediaItem])
def items(request: Request) -> list[MediaItem]:
    st = get_state(request)
    return [_enrich(it, st) for it in st.library.all()]


@router.get("/items/page")
def items_page(request: Request, limit: int = Query(default=500, ge=1, le=500),
               offset: int = Query(default=0, ge=0, le=10_000),
               revision: str | None = Query(default=None, max_length=64)) -> dict:
    st = get_state(request)
    try:
        values, total, current = st.library.page(offset, limit, revision)
    except ValueError as error:
        raise HTTPException(409, "Le catalogue a change ; recommencer depuis la premiere page") from error
    return {"items": [_enrich(item, st) for item in values], "total": total,
            "revision": current, "next_offset": offset + len(values) if offset + len(values) < total else None}


@router.get("/items/{item_id}", response_model=MediaItem)
def item(item_id: str, request: Request) -> MediaItem:
    st = get_state(request)
    it = st.library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")
    return _enrich(it, st)


@router.get("/items/{item_id}/thumbnail")
def item_thumbnail(item_id: str, request: Request):
    """Capture locale disponible même lorsqu'une affiche distante est référencée."""
    st = get_state(request)
    it = st.library.get(item_id)
    if not it or it.kind not in {"movie", "series"}:
        raise HTTPException(404, "Miniature introuvable")
    try:
        path = st.thumbnails.jpeg(it)
    except ThumbnailBusy:
        raise HTTPException(503, "Génération occupée", headers={"Retry-After": "1"})
    if not path:
        raise HTTPException(404, "Miniature indisponible")
    return FileResponse(
        path,
        media_type="image/jpeg",
        headers={
            "Cache-Control": "private, max-age=86400",
            "ETag": f'"thumb-{item.id}-{path.stat().st_mtime_ns}"',
        },
    )


@router.get("/items/{item_id}/probe", response_model=MediaInfo)
def item_probe(item_id: str, request: Request) -> MediaInfo:
    require_local_admin(request)
    st = get_state(request)
    it = st.library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")
    return probe(st.cfgm.cfg.ffmpeg.ffprobe_path, it.path)


@router.get("/items/{item_id}/metadata", response_model=MovieMetadata)
def item_metadata(item_id: str, request: Request, refresh: bool = False) -> MovieMetadata:
    """Métadonnées enrichies (TMDB). `?refresh=true` force une nouvelle résolution."""
    st = get_state(request)
    it = st.library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")
    # Le rafraichissement unitaire force est un outil d'administration locale.
    return st.metadata.get(it, force=refresh and is_loopback_request(request))


@router.post("/refresh-metadata")
def refresh_metadata(request: Request) -> dict:
    """Relance l'enrichissement TMDB de toute la bibliothèque en arrière-plan."""
    st = get_state(request)
    started = st.enrich_library_async(force=True)
    return {"status": "started" if started else "already_running"}
