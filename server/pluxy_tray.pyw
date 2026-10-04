"""
Pluxy — mini-application de la barre des tâches Windows.

Lancée au démarrage (à la place d'ouvrir un navigateur) : démarre le serveur en
arrière-plan et pose une ICÔNE dans la zone de notification, avec un menu pour ouvrir
l'interface de configuration dans une FENÊTRE ÉPURÉE (Edge en mode application — intégré
à Windows, PAS Chrome), la rouvrir dans le navigateur, redémarrer ou quitter le serveur.

Sans console (extension .pyw + lancement par pythonw). Dépendances (pystray, Pillow)
auto-installées au 1er lancement si absentes.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
import webbrowser
from pathlib import Path
from urllib.request import urlopen

BASE = Path(__file__).resolve().parent            # .../server
ROOT = BASE.parent                                # racine du dépôt
CREATE_NO_WINDOW = 0x08000000


def _read_port() -> int:
    from run import _read_host_port
    return _read_host_port()[1]


PORT = _read_port()
URL = f"http://localhost:{PORT}"

# --- Instance unique (le 2e lancement ouvre juste l'UI puis sort) ------------ #
_lock_file = None


def _acquire_single_instance() -> bool:
    """Stable data-directory lock, independent of the user-configured HTTP port."""
    from pluxy.paths import data_dir
    global _lock_file
    lock_path = data_dir() / "tray.lock"
    lock_path.touch(exist_ok=True)
    lock_file = lock_path.open("r+b")
    try:
        if os.name == "nt":
            import msvcrt
            if lock_path.stat().st_size == 0:
                lock_file.write(b"\0")
                lock_file.flush()
            lock_file.seek(0)
            msvcrt.locking(lock_file.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        _lock_file = lock_file
        _record_active_port()
        return True
    except OSError:
        lock_file.close()
        return False


def _record_active_port() -> None:
    if _lock_file is not None:
        # The first byte is locked. Readers of a second instance read only after it.
        _lock_file.seek(1)
        _lock_file.write(str(PORT).encode("ascii"))
        _lock_file.truncate()
        _lock_file.flush()


def _existing_instance_port() -> int:
    from pluxy.paths import data_dir
    try:
        with (data_dir() / "tray.lock").open("rb") as handle:
            handle.seek(1)
            active_port = int(handle.read(16))
        if 1 <= active_port <= 65535:
            return active_port
    except (OSError, ValueError):
        pass
    return PORT


# --- Serveur (sous-processus géré par la tray) ------------------------------- #
_server: subprocess.Popen | None = None


def _server_up() -> bool:
    try:
        with urlopen(f"http://127.0.0.1:{PORT}/health", timeout=1.0) as response:
            health = json.loads(response.read(4096))
            return isinstance(health, dict) and health.get("app") == "pluxy" and health.get("status") == "ok"
    except (OSError, ValueError):
        return False


def start_server() -> None:
    global _server, PORT, URL
    if _server and _server.poll() is None:
        return
    # Port changes take effect when the managed server is restarted.
    PORT = _read_port()
    URL = f"http://localhost:{PORT}"
    _record_active_port()
    if _server_up():
        return
    _server = subprocess.Popen(
        [sys.executable, str(BASE / "run.py")],
        cwd=str(BASE), creationflags=CREATE_NO_WINDOW,
    )


def wait_for_server(timeout: float = 12.0) -> bool:
    """Évite d'ouvrir une fenêtre vide pendant le démarrage de FastAPI."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if _server_up():
            return True
        time.sleep(0.2)
    return False


def stop_server() -> None:
    global _server
    if _server and _server.poll() is None:
        _server.terminate()
        try:
            _server.wait(6)
        except Exception:
            _server.kill()
            _server.wait(6)
    _server = None


def restart_server() -> None:
    stop_server()
    time.sleep(0.6)
    start_server()


# --- Ouverture de l'interface ------------------------------------------------ #
def _edge() -> str | None:
    for p in (
        os.path.expandvars(r"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"),
        os.path.expandvars(r"%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"),
    ):
        if os.path.exists(p):
            return p
    return shutil.which("msedge")


def open_ui(start_if_needed: bool = True) -> None:
    """Fenêtre épurée façon application (Edge --app), repli sur le navigateur."""
    if start_if_needed and not _server_up():
        start_server()
        wait_for_server()
    edge = _edge()
    if edge:
        try:
            subprocess.Popen([edge, f"--app={URL}", "--window-size=1200,860"])
            return
        except Exception:
            pass
    open_browser()


def open_browser() -> None:
    try:
        webbrowser.open(URL)
    except Exception:
        pass


# --- Icône + dépendances ----------------------------------------------------- #
def _ensure_deps() -> bool:
    try:
        import pystray  # noqa: F401
        import PIL  # noqa: F401
        return True
    except Exception:
        try:
            subprocess.run(
                [sys.executable, "-m", "pip", "install", "--quiet",
                 "--disable-pip-version-check", "pystray>=0.19", "Pillow>=10"],
                creationflags=CREATE_NO_WINDOW, timeout=240,
            )
            import importlib
            importlib.invalidate_caches()
            import pystray  # noqa: F401
            import PIL  # noqa: F401
            return True
        except Exception:
            return False


def _icon_image():
    from PIL import Image
    for p in (ROOT / "assets" / "pluxy.ico",
              BASE / "pluxy" / "web" / "logo.png",
              ROOT / "assets" / "pluxy-logo.png"):
        try:
            if p.exists():
                return Image.open(p).convert("RGBA")
        except Exception:
            pass
    return Image.new("RGBA", (64, 64), (229, 160, 13, 255))   # repli : pastille or


def main() -> None:
    global PORT, URL
    if not _acquire_single_instance():
        PORT = _existing_instance_port()
        URL = f"http://localhost:{PORT}"
        open_ui(start_if_needed=False)
        return
    start_server()
    wait_for_server()
    # L'application est immédiatement prête après un double-clic, sans obliger
    # l'utilisateur à chercher l'icône de zone de notification.
    open_ui()
    if not _ensure_deps():
        return                    # tray impossible -> le serveur tourne au moins

    import pystray
    from pystray import Menu, MenuItem as Item

    icon = pystray.Icon(
        "pluxy", _icon_image(), "Pluxy — serveur média",
        menu=Menu(
            Item("Ouvrir l'interface", lambda i, it: open_ui(), default=True),
            Item("Ouvrir dans le navigateur", lambda i, it: open_browser()),
            Menu.SEPARATOR,
            Item("Démarrer le serveur", lambda i, it: start_server(),
                 enabled=lambda item: not _server_up()),
            Item("Arrêter le serveur", lambda i, it: stop_server(),
                 enabled=lambda item: _server_up()),
            Item("Redémarrer le serveur", lambda i, it: restart_server()),
            Menu.SEPARATOR,
            Item("Quitter Pluxy", lambda i, it: _quit(i)),
        ),
    )
    icon.run()


def _quit(icon) -> None:
    try:
        stop_server()
    finally:
        icon.stop()


if __name__ == "__main__":
    main()
