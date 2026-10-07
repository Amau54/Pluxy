"""API bibliothèque — scan, listing, détail média, métadonnées."""
from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, HTTPException, Request, Query
from fastapi.responses import FileResponse

from ..models import MediaInfo, MediaItem, MovieMetadata
from ..probe import probe
from ..state import AppState, get_state
from ..security import is_loopback_request, require_local_admin
from ..thumbnails import ThumbnailBusy
from ..catalogue import Catalogue, Query as CatalogueQuery, public_item

router = APIRouter(prefix="/api/library", tags=["library"])


def _enrich(item: MediaItem, st: AppState) -> MediaItem:
    """Copie publique enrichie : poster/année depuis le cache + chemin disque MASQUÉ.

    On ne renvoie jamais le chemin absolu réel au client (fuite d'info sur le
    serveur). On travaille sur une COPIE pour ne pas muter l'objet indexé.
    """
    return public_item(item, st.metadata.cached(item.id), st.watch.get(item.id))


Kind = Literal["all", "video", "movie", "series", "clip", "photo", "audio"]


def _catalogue(st: AppState) -> Catalogue:
    # Les tests et intégrations qui construisent leur propre AppState gardent
    # le même contrat, sans qu'une route retourne un instantané périmé.
    if not hasattr(st, "catalogue"):
        st.catalogue = Catalogue(st.library, st.metadata, st.watch)
    return st.catalogue


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
    return _catalogue(st).page(CatalogueQuery(), 0, 10_000)[0]


@router.get("/items/page")
def items_page(request: Request, limit: int = Query(default=500, ge=1, le=500),
               offset: int = Query(default=0, ge=0, le=10_000),
               revision: str | None = Query(default=None, max_length=64),
               kind: Kind = "all", q: str = Query(default="", max_length=256),
               watched: Literal["all", "unwatched", "watched", "in_progress"] = "all",
               genre: list[str] = Query(default=[], max_length=32),
               year: int | None = Query(default=None, ge=1, le=9999),
               duration_max_minutes: int | None = Query(default=None, ge=1, le=100_000),
               resolution: Literal["all", "4k", "1080p", "720p", "sd"] = "all",
               hdr_only: bool = False,
               sort: Literal["title", "added", "year", "duration"] = "title",
               descending: bool = False,
               title_prefix: str = Query(default="", pattern="^[A-Za-z]?$")) -> dict:
    st = get_state(request)
    if any(len(value) > 128 for value in genre):
        raise HTTPException(422, "Genre trop long")
    try:
        values, total, current = _catalogue(st).page(
            CatalogueQuery(kind, q, watched, tuple(genre), year, duration_max_minutes,
                           resolution, hdr_only, sort, descending, title_prefix), offset, limit, revision)
    except ValueError as error:
        raise HTTPException(409, "Le catalogue a change ; recommencer depuis la premiere page") from error
    return {"items": values, "total": total,
            "revision": current, "next_offset": offset + len(values) if offset + len(values) < total else None}


@router.get("/facets")
def facets(request: Request, kind: Kind = "all") -> dict:
    return _catalogue(get_state(request)).facets(kind)


@router.get("/home")
def home(request: Request) -> dict:
    return _catalogue(get_state(request)).home()


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
    if not it or it.kind not in {"movie", "series", "clip", "photo", "audio"}:
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
            "ETag": f'"thumb-{it.id}-{path.stat().st_mtime_ns}"',
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
