"""API serveur — informations réseau pour l'auto-configuration du client."""
from __future__ import annotations

from fastapi import APIRouter, Request

from ..netinfo import all_lan_ips, primary_lan_ip
from .. import __version__
from ..state import get_state
from ..tools import ffmpeg_available, ffprobe_available

router = APIRouter(prefix="/api/server", tags=["server"])


@router.get("/info")
def server_info(request: Request) -> dict:
    st = get_state(request)
    cfg = st.cfgm.cfg
    primary = primary_lan_ip()
    return {
        "app": "pluxy",
        "name": cfg.server.name,
        "ip": primary,
        "port": st.active_port,
        "version": __version__,
        "platform": "pc",
        "primary_ip": primary,
        "ips": all_lan_ips(),
        "base_url": f"http://{primary}:{st.active_port}",
        "configured_port": cfg.server.port,
        "restart_required": st.active_port != cfg.server.port or st.active_host != cfg.server.host,
        "discovery_port": cfg.discovery.udp_port,
        "ffmpeg": ffmpeg_available(cfg),
        "ffprobe": ffprobe_available(cfg),
        "transcoding_available": ffmpeg_available(cfg),
        "scanning": st.library.is_scanning,
        "media_count": len(st.library.all()),
    }
