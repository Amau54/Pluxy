"""
Emplacement STABLE des données utilisateur (hors du dossier projet).

Garantit que la configuration (clé TMDB, dossiers), l'index de bibliothèque, le
cache de métadonnées et l'état de lecture SURVIVENT à une mise à jour / un
remplacement du dossier Pluxy.

Windows : %LOCALAPPDATA%\\Pluxy   ·   Linux/Mac : ~/.local/share/Pluxy
"""
from __future__ import annotations

import os
import tempfile
import time
from pathlib import Path

_REPLACE_ATTEMPTS = 6
_WINDOWS_REPLACE_ERRORS = {5, 32, 33}  # Accès refusé / partage / verrouillage.


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


def atomic_write_text(path: Path, text: str) -> None:
    """
    Écriture ATOMIQUE : écrit dans un fichier temporaire du même dossier puis
    `os.replace` (atomique sur le même volume). Une coupure pendant l'écriture
    ne corrompt jamais le fichier cible (config / index / reprise préservés).
    """
    # Un nom unique évite que deux sauvegardes concurrentes tronquent le même
    # temporaire ou tentent de remplacer un fichier déjà déplacé par l'autre.
    tmp: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent,
            prefix=f".{path.name}.", suffix=".tmp", delete=False,
        ) as handle:
            tmp = Path(handle.name)
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        for attempt in range(_REPLACE_ATTEMPTS):
            try:
                os.replace(tmp, path)
                break
            except PermissionError as error:
                # Windows peut refuser brièvement une destination remplacée par
                # un autre writer ou inspectée par l'antivirus. Aucun retry pour
                # les autres erreurs (ni pour EACCES POSIX, sans winerror).
                if (getattr(error, "winerror", None) not in _WINDOWS_REPLACE_ERRORS
                        or attempt == _REPLACE_ATTEMPTS - 1):
                    raise
                time.sleep(0.01 * (2 ** attempt))
    finally:
        if tmp is not None:
            tmp.unlink(missing_ok=True)
