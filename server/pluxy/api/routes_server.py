"""API serveur — informations réseau pour l'auto-configuration du client."""
from __future__ import annotations

from fastapi import APIRouter, Request

from ..netinfo import all_lan_ips, primary_lan_ip
from ..state import get_state

router = APIRouter(prefix="/api/server", tags=["server"])


@router.get("/info")
def server_info(request: Request) -> dict:
    st = get_state(request)
    cfg = st.cfgm.cfg
    primary = primary_lan_ip()
    return {
        "app": "pluxy",
        "name": cfg.server.name,
        "port": cfg.server.port,
        "primary_ip": primary,
        "ips": all_lan_ips(),
        "base_url": f"http://{primary}:{cfg.server.port}",
        "discovery_port": cfg.discovery.udp_port,
    }
