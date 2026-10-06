"""API de configuration (Workflow 3 & 4) — lecture/écriture des réglages."""
from __future__ import annotations
import asyncio

from fastapi import APIRouter, HTTPException, Request

from ..state import get_state
from ..tools import ffmpeg_available
from ..folder_picker import FolderPickerBusy, choose_media_folder
from ..security import (
    filtered_remote_settings,
    is_loopback_request,
    require_local_admin,
    validate_remote_patch,
)

router = APIRouter(prefix="/api/settings", tags=["settings"])


@router.post("/pick-folder")
def pick_folder(request: Request) -> dict:
    """Ouvre le sélecteur Windows uniquement depuis la console locale.

    Le navigateur ne peut pas choisir un chemin de disque pour des raisons de
    sécurité. Cette petite passerelle locale évite à l'utilisateur de saisir un
    chemin et reste inaccessible depuis la TV ou le réseau.
    """
    require_local_admin(request)
    try:
        return {"path": choose_media_folder()}
    except FolderPickerBusy as exc:
        raise HTTPException(409, str(exc)) from exc
    except Exception as exc:
        raise HTTPException(503, "Le sélecteur de dossier Windows est indisponible.") from exc


@router.get("")
def read_settings(request: Request) -> dict:
    """Configuration complète en local, vue sans secrets pour la télévision."""
    cfg = get_state(request).cfgm.cfg
    if is_loopback_request(request):
        return cfg.model_dump()
    public = filtered_remote_settings(cfg)
    public["server"]["transcoding_available"] = ffmpeg_available(cfg)
    return public


@router.patch("")
async def patch_settings(request: Request) -> dict:
    """
    Applique un patch partiel envoyé par l'UI. Exemple :
        { "transcoding": { "hardware_acceleration": false, "max_bitrate_mbps": 30 } }

    SÉCURITÉ : les chemins d'exécutables (ffmpeg/ffprobe) et le dossier de cache
    NE sont PAS modifiables via l'API (vecteur d'exécution de commande arbitraire).
    Ils ne se changent que dans le fichier config local, sur la machine serveur.
    """
    try:
        patch = await request.json()
    except Exception as exc:
        raise HTTPException(400, "JSON de configuration invalide.") from exc
    if not isinstance(patch, dict) or not patch:
        raise HTTPException(400, "Patch de configuration invalide.")
    if not is_loopback_request(request):
        patch = validate_remote_patch(patch)
    discovery_changed = isinstance(patch, dict) and "discovery" in patch
    autostart_changed = (
        isinstance(patch, dict)
        and isinstance(patch.get("server"), dict)
        and "autostart" in patch["server"]
    )
    if isinstance(patch, dict):
        ff = patch.get("ffmpeg")
        if isinstance(ff, dict):
            ff.pop("ffmpeg_path", None)
            ff.pop("ffprobe_path", None)
        srv = patch.get("server")
        if isinstance(srv, dict):
            srv.pop("transcode_temp_dir", None)
    st = get_state(request)
    try:
        cfg = await asyncio.to_thread(st.cfgm.update, patch)
    except (TypeError, ValueError) as exc:
        raise HTTPException(422, "Valeur de configuration invalide.") from exc
    except OSError as exc:
        raise HTTPException(503, "Impossible de sauvegarder la configuration sur le disque.") from exc
    # Redémarre le répondeur de découverte si ses réglages ont changé.
    if discovery_changed:
        try:
            st.discovery.stop()
            st.discovery.start()
        except Exception:
            pass
    # Crée/supprime l'entrée de démarrage Windows si le réglage a changé.
    if autostart_changed:
        try:
            from .. import autostart
            autostart.apply(cfg.server.autostart)
        except Exception:
            pass
    if is_loopback_request(request):
        return cfg.model_dump()
    public = filtered_remote_settings(cfg)
    public["server"]["transcoding_available"] = ffmpeg_available(cfg)
    return public


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
        "server_kind": "pc",
        "transcoding_available": ffmpeg_available(cfg),
        "direct_stream_available": ffmpeg_available(cfg),
    }
