"""Détection des adresses IPv4 LAN du PC serveur (pour l'auto-config client)."""
from __future__ import annotations

import socket
from typing import List


def primary_lan_ip() -> str:
    """
    IP LAN « principale » : celle utilisée pour sortir vers le réseau.
    Astuce socket UDP (aucun paquet réellement envoyé).
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def all_lan_ips() -> List[str]:
    """Toutes les IPv4 non-loopback de la machine (multi-cartes / Wi-Fi + Ethernet)."""
    ips: list[str] = []
    primary = primary_lan_ip()
    if primary and not primary.startswith("127."):
        ips.append(primary)
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    return ips or ["127.0.0.1"]
