"""API de configuration (Workflow 3 & 4) — lecture/écriture des réglages."""
from __future__ import annotations

from fastapi import APIRouter, Request

from ..config import PluxyConfig
from ..state import get_state

router = APIRouter(prefix="/api/settings", tags=["settings"])


@router.get("", response_model=PluxyConfig)
def read_settings(request: Request) -> PluxyConfig:
    """Renvoie la configuration complète (alimente l'UI)."""
    return get_state(request).cfgm.cfg


@router.patch("", response_model=PluxyConfig)
async def patch_settings(request: Request) -> PluxyConfig:
    """
    Applique un patch partiel envoyé par l'UI. Exemple :
        { "transcoding": { "hardware_acceleration": false, "max_bitrate_mbps": 30 } }
    """
    patch = await request.json()
    return get_state(request).cfgm.update(patch)


@router.get("/client", tags=["settings"])
def client_runtime(request: Request) -> dict:
    """
    Paramètres consommés par le client Android TV au démarrage :
    tailles de buffer ExoPlayer + préférences sous-titres.
    """
    cfg = get_state(request).cfgm.cfg
    return {
        "buffer": cfg.client_buffer.model_dump(),
        "subtitles": cfg.subtitles.model_dump(),
        "server_name": cfg.server.name,
    }
