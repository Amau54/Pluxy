"""
Emplacement STABLE des données utilisateur (hors du dossier projet).

Garantit que la configuration (clé TMDB, dossiers), l'index de bibliothèque, le
cache de métadonnées et l'état de lecture SURVIVENT à une mise à jour / un
remplacement du dossier Pluxy.

Windows : %LOCALAPPDATA%\\Pluxy   ·   Linux/Mac : ~/.local/share/Pluxy
"""
from __future__ import annotations

import os
from pathlib import Path


def data_dir() -> Path:
    base = (
        os.environ.get("PLUXY_DATA_DIR")
        or os.environ.get("LOCALAPPDATA")
        or os.environ.get("XDG_DATA_HOME")
        or str(Path.home() / ".local" / "share")
    )
    d = Path(base) / "Pluxy"
    d.mkdir(parents=True, exist_ok=True)
    return d
