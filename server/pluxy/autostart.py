"""
Démarrage automatique du serveur Pluxy à l'ouverture de session Windows.

Mécanisme : une valeur dans la clé de registre par-utilisateur
`HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run` (aucun droit admin requis ;
c'est l'emplacement standard, utilisé aussi par Plex/Steam/…). Le serveur est lancé
SANS fenêtre via `pythonw.exe` du venv. Pilotée par le réglage `server.autostart`,
l'entrée est créée/supprimée au démarrage du serveur et à chaque changement du réglage.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
_NAME = "Pluxy"


def supported() -> bool:
    return os.name == "nt"


def _launch_command() -> str:
    """Commande de lancement sans console : pythonw du venv + la mini-app de la barre
    des tâches (qui démarre le serveur ET pose l'icône). Chemins absolus."""
    exe = Path(sys.executable)
    pyw = exe.with_name("pythonw.exe")
    py = pyw if pyw.exists() else exe
    server = Path(__file__).resolve().parent.parent          # .../server
    desktop = server.parent / "Pluxy.exe"
    if desktop.is_file():
        return f'"{desktop}" --server --background'
    target = server / "pluxy_tray.pyw"
    if not target.exists():                                   # repli : serveur seul
        target = server / "run.py"
    return f'"{py}" "{target}"'


def is_enabled() -> bool:
    if not supported():
        return False
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY) as k:
            winreg.QueryValueEx(k, _NAME)
        return True
    except OSError:
        return False


def apply(enabled: bool) -> bool:
    """Crée (enabled) ou supprime l'entrée de démarrage. Renvoie True si OK."""
    if not supported():
        return False
    try:
        import winreg
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER, _RUN_KEY, 0, winreg.KEY_ALL_ACCESS
        ) as k:
            if enabled:
                winreg.SetValueEx(k, _NAME, 0, winreg.REG_SZ, _launch_command())
            else:
                try:
                    winreg.DeleteValue(k, _NAME)
                except FileNotFoundError:
                    pass
        return True
    except Exception:
        return False
