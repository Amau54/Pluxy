"""API bibliothèque — scan, listing, détail média."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request

from ..models import MediaInfo, MediaItem
from ..probe import probe
from ..state import get_state

router = APIRouter(prefix="/api/library", tags=["library"])


@router.post("/scan")
def scan(request: Request) -> dict:
    count = get_state(request).library.scan()
    return {"indexed": count}


@router.get("/items", response_model=list[MediaItem])
def items(request: Request) -> list[MediaItem]:
    return get_state(request).library.all()


@router.get("/items/{item_id}", response_model=MediaItem)
def item(item_id: str, request: Request) -> MediaItem:
    it = get_state(request).library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")
    return it


@router.get("/items/{item_id}/probe", response_model=MediaInfo)
def item_probe(item_id: str, request: Request) -> MediaInfo:
    """Analyse détaillée des pistes (debug / fiche technique)."""
    st = get_state(request)
    it = st.library.get(item_id)
    if not it:
        raise HTTPException(404, "Média introuvable")
    return probe(st.cfgm.cfg.ffmpeg.ffprobe_path, it.path)
