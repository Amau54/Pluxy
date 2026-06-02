"""
Découverte automatique du serveur sur le LAN (broadcast UDP).

Le client envoie un datagramme "PLUXY_DISCOVERY_V1" en broadcast sur `udp_port`.
Le serveur répond (unicast) avec un JSON décrivant comment l'atteindre :
    {"app":"pluxy","name":..., "ip":..., "port":..., "version":...}

=> Le client n'a JAMAIS besoin de connaître l'IP à l'avance.
"""
from __future__ import annotations

import json
import socket
import threading

from . import __version__
from .config import ConfigManager
from .netinfo import primary_lan_ip

MAGIC = b"PLUXY_DISCOVERY_V1"


class DiscoveryResponder:
    def __init__(self, cfgm: ConfigManager):
        self.cfgm = cfgm
        self._sock: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._running = False

    def start(self) -> None:
        cfg = self.cfgm.cfg
        if not cfg.discovery.enabled:
            return
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        try:
            self._sock.bind(("0.0.0.0", cfg.discovery.udp_port))
        except OSError:
            self._sock = None
            return
        self._sock.settimeout(1.0)
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def _loop(self) -> None:
        while self._running and self._sock:
            try:
                data, addr = self._sock.recvfrom(1024)
            except socket.timeout:
                continue
            except OSError:
                break
            if not data.startswith(MAGIC):
                continue
            cfg = self.cfgm.cfg
            reply = json.dumps({
                "app": "pluxy",
                "name": cfg.server.name,
                "ip": primary_lan_ip(),
                "port": cfg.server.port,
                "version": __version__,
            }).encode("utf-8")
            try:
                self._sock.sendto(reply, addr)
            except OSError:
                pass

    def stop(self) -> None:
        self._running = False
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None
