"""Frontiere de securite de Pluxy : LAN prive, sans authentification.

Le catalogue et les flux restent volontairement accessibles a tous les appareils
du reseau domestique. L'administration sensible, elle, reste sur la machine qui
heberge le serveur.
"""
from __future__ import annotations

import ipaddress
import hashlib
from typing import Any
from urllib.parse import urlsplit

from fastapi import HTTPException, Request


_IPV4_LAN = tuple(
    ipaddress.ip_network(value)
    for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16")
)
_IPV6_ULA = ipaddress.ip_network("fc00::/7")


def _ip(value: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    # Une adresse IPv6 peut contenir un identifiant de zone (fe80::1%12).
    try:
        return ipaddress.ip_address(value.split("%", 1)[0])
    except (TypeError, ValueError):
        return None


def is_lan_address(value: str) -> bool:
    """Accepte loopback, RFC1918, link-local et IPv6 ULA; refuse Internet."""
    address = _ip(value)
    if address is None:
        return False
    if address.is_loopback or address.is_link_local:
        return True
    if isinstance(address, ipaddress.IPv4Address):
        return any(address in network for network in _IPV4_LAN)
    return address in _IPV6_ULA


def is_loopback_request(request: Request) -> bool:
    host = request.client.host if request.client else ""
    address = _ip(host)
    hostname = request.url.hostname or ""
    host_address = _ip(hostname)
    local_host = hostname.lower() == "localhost" or bool(host_address and host_address.is_loopback)
    return bool(address and address.is_loopback and local_host)


def trusted_host(request: Request) -> bool:
    hostname = request.url.hostname or ""
    if hostname.lower() == "localhost" or is_lan_address(hostname):
        return True
    state = getattr(request.app.state, "pluxy", None)
    configured = state.cfgm.cfg.server.host if state else ""
    return bool(configured and configured not in {"0.0.0.0", "::"} and hostname == configured)


def validate_browser_origin(request: Request) -> None:
    if request.method in {"GET", "HEAD", "OPTIONS"}:
        return
    origin = request.headers.get("origin")
    if origin is None:
        if request.headers.get("sec-fetch-site") == "cross-site":
            raise HTTPException(403, "Origine de requete interdite")
        return
    try:
        parsed = urlsplit(origin)
        same = (parsed.scheme == request.url.scheme and parsed.hostname == request.url.hostname
                and (parsed.port or (443 if parsed.scheme == "https" else 80))
                == (request.url.port or (443 if request.url.scheme == "https" else 80)))
    except ValueError:
        same = False
    if not same:
        raise HTTPException(403, "Origine de requete interdite")


def session_token(request: Request) -> str | None:
    from .watchstate import SESSION_TOKEN_RE
    header = request.headers.get("x-pluxy-session")
    query = request.query_params.get("session")
    if header is not None and query is not None and header != query:
        raise HTTPException(400, "Session contradictoire")
    token = header if header is not None else query
    if token is not None and not SESSION_TOKEN_RE.fullmatch(token):
        raise HTTPException(422, "Session invalide")
    return token


def playback_key(request: Request) -> str:
    token = session_token(request)
    return token or "legacy-" + hashlib.sha256(client_key(request).encode()).hexdigest()[:16]


def require_local_admin(request: Request) -> None:
    if not is_loopback_request(request):
        raise HTTPException(
            403,
            "Administration disponible uniquement depuis le PC serveur (localhost).",
        )


def client_key(request: Request) -> str:
    """Cle bornee pour isoler les decisions de lecture de chaque appareil."""
    return (request.client.host if request.client else "unknown")[:64]


def filtered_remote_settings(cfg: Any) -> dict:
    """Vue necessaire au lecteur TV, sans chemin local ni secret."""
    return {
        "transcoding": {
            key: getattr(cfg.transcoding, key)
            for key in (
                "hardware_acceleration",
                "preset",
                "force_transcode",
                "prefer_direct_play",
                "max_bitrate_mbps",
                "hdr_tone_mapping",
            )
        },
        "audio": {
            key: getattr(cfg.audio, key)
            for key in (
                "force_audio_passthrough",
                "target_codec",
                "target_channels",
                "bitrate_kbps",
                "downmix_lossless",
            )
        },
        "network": {
            key: getattr(cfg.network, key)
            for key in (
                "direct_play_enabled",
                "direct_stream_enabled",
                "hls_segment_duration",
            )
        },
        "client_buffer": {
            "min_buffer_ms": cfg.client_buffer.min_buffer_ms,
            "max_buffer_ms": cfg.client_buffer.max_buffer_ms,
        },
        "server": {
            "name": cfg.server.name,
            "autostart": cfg.server.autostart,
            "platform": "pc",
            "transcoding_available": True,
        },
    }


_REMOTE_PATCH_FIELDS = {
    "transcoding": {
        "hardware_acceleration",
        "preset",
        "force_transcode",
        "prefer_direct_play",
        "max_bitrate_mbps",
        "hdr_tone_mapping",
    },
    "audio": {
        "force_audio_passthrough",
        "target_codec",
        "target_channels",
        "bitrate_kbps",
        "downmix_lossless",
    },
    "network": {"direct_play_enabled", "direct_stream_enabled", "hls_segment_duration"},
    "client_buffer": {"max_buffer_ms"},
    "server": {"autostart"},
}


def validate_remote_patch(patch: object) -> dict:
    """Refuse tout champ que le lecteur TV n'a aucune raison de modifier."""
    if not isinstance(patch, dict) or not patch:
        raise HTTPException(400, "Patch de configuration invalide.")
    clean: dict = {}
    for section, values in patch.items():
        allowed = _REMOTE_PATCH_FIELDS.get(section)
        if allowed is None or not isinstance(values, dict) or not values:
            raise HTTPException(403, f"Section distante interdite : {section}")
        forbidden = set(values) - allowed
        if forbidden:
            names = ", ".join(sorted(str(value) for value in forbidden))
            raise HTTPException(403, f"Reglage distant interdit : {section}.{names}")
        clean[section] = dict(values)
    return clean
