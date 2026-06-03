"""API bibliothèque — scan, listing, détail média, métadonnées."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request

from ..models import MediaInfo, MediaItem, MovieMetadata
from ..probe import probe
from ..state import AppState, get_state

router = APIRouter(prefix="/api/library", tags=["library"])


def _enrich(item: MediaItem, st: AppState) -> MediaItem:
    """Copie publique enrichie : poster/année depuis le cache + chemin disque MASQUÉ.

    On ne renvoie jamais le chemin absolu réel au client (fuite d'info sur le
    serveur). On travaille sur une COPIE pour ne pas muter l'objet indexé.
    """
    pub = item.model_copy()
    pub.path = ""                              # ne pas exposer le chemin serveur
    meta = st.metadata.cached(item.id)
    if meta:
        pub.year = meta.year
        pub.poster_url = meta.poster_url
        pub.has_metadata = meta.matched
    return pub


@router.post("/scan")
def scan(request: Request) -> dict:
    """Lance un (re)scan en arrière-plan ; renvoie immédiatement."""
    st = get_state(request)
    st.scan_async()
    return {"status": "scanning", "current_count": len(st.library.all())}


@router.get("/scan-status")
def scan_status(request: Request) -> dict:
    st = get_state(request)
    return {"scanning": st.library.is_scanning, "count": len(st.library.all())}


@router.get("/items", response_model=list[MediaItem])
def items(request: Request) -> list[MediaItem]:
    st = get_state(request)
    return [_enrich(it, st) for it in st.library.all()]


@router.get("/items/{item_id}", response_model=MediaItem)
def item(item_id: str, request: Request) -> MediaItem:
    st = get_state(request)
    it = st.library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")
    return _enrich(it, st)


@router.get("/items/{item_id}/probe", response_model=MediaInfo)
def item_probe(item_id: str, request: Request) -> MediaInfo:
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
    return st.metadata.get(it, force=refresh)


@router.post("/refresh-metadata")
def refresh_metadata(request: Request) -> dict:
    """Relance l'enrichissement TMDB de toute la bibliothèque en arrière-plan."""
    st = get_state(request)
    st.enrich_library_async(force=True)
    return {"status": "started"}
