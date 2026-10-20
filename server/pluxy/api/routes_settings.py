"""API de configuration (Workflow 3 & 4) — lecture/écriture des réglages."""
from __future__ import annotations
import asyncio

from fastapi import APIRouter, HTTPException, Request

from ..state import get_state
from ..tools import ffmpeg_available, ffprobe_available
from ..folder_picker import FolderPickerBusy, choose_media_folder
from ..security import (
    filtered_remote_settings,
    is_loopback_request,
    require_local_admin,
    validate_remote_patch,
)

router = APIRouter(prefix="/api/settings", tags=["settings"])


@router.get("/diagnostics")
def diagnostics(request: Request) -> dict:
    require_local_admin(request)
    from ..tmdb_auth import tmdb_status
    state = get_state(request)
    cfg = state.cfgm.cfg
    videos = [item for item in state.library.all() if item.kind in {"movie", "series"}]
    posters = sum(bool((meta := state.metadata.cached(item.id)) and meta.poster_url) for item in videos)
    return {"ffmpeg": ffmpeg_available(cfg), "ffprobe": ffprobe_available(cfg),
            "tmdb": tmdb_status(cfg.metadata.tmdb_api_key) if cfg.metadata.enabled else "disabled",
            "videos_to_match": len(videos), "posters": posters,
            "metadata": getattr(state, "enrich_status", {})}


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


@router.get('/watched-organization')
def watched_organization(request: Request, preview: bool = False):
    require_local_admin(request)
    organizer = get_state(request).organizer
    status = organizer.status()
    status['already_watched_count'] = len(organizer.candidates(include_existing=True)) if preview and status['available'] else 0
    return status


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
    organization = patch.pop('watched_organization', None)
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
    organizer = getattr(st, 'organizer', None)
    if isinstance(patch.get('server'), dict) and 'organize_watched' in patch['server'] and type(patch['server']['organize_watched']) is not bool:
        raise HTTPException(422, 'Le rangement automatique doit être activé ou désactivé.')
    enabling = isinstance(patch.get('server'), dict) and patch['server'].get('organize_watched') is True
    if enabling:
        require_local_admin(request)
        if not organizer or not organizer.available():
            raise HTTPException(422, 'Le rangement dans Vu nécessite un seul profil.')
        if not st.cfgm.cfg.server.organize_watched:
            if not isinstance(organization, dict) or type(organization.get('include_existing')) is not bool:
                raise HTTPException(409, 'Choisissez si les médias déjà vus doivent aussi être rangés.')
            try:
                await asyncio.to_thread(organizer.configure, organization['include_existing'])
            except OSError as exc:
                raise HTTPException(503, 'Impossible de préparer le rangement des médias.') from exc
    previous_sources = (list(st.cfgm.cfg.server.media_dirs), dict(st.cfgm.cfg.server.media_dir_types))
    try:
        cfg = await asyncio.to_thread(st.cfgm.update, patch)
    except (TypeError, ValueError) as exc:
        raise HTTPException(422, "Valeur de configuration invalide.") from exc
    except OSError as exc:
        raise HTTPException(503, "Impossible de sauvegarder la configuration sur le disque.") from exc
    if previous_sources != (cfg.server.media_dirs, cfg.server.media_dir_types):
        st.scan_async()
    if organizer:
        organizer.wake.set()
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
    from ..conversion import available_profiles
    return {
        "buffer": cfg.client_buffer.model_dump(),
        "subtitles": cfg.subtitles.model_dump(),
        "server_name": cfg.server.name,
        "server_kind": "pc",
        "transcoding_available": ffmpeg_available(cfg),
        "direct_stream_available": ffmpeg_available(cfg),
        "direct_stream_seek_available": ffmpeg_available(cfg),
        "conversion_profiles": available_profiles(cfg),
    }
