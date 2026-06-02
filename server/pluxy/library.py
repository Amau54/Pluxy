"""Scanner de bibliothèque : indexe les fichiers média + sous-titres externes.

L'index est PERSISTÉ sur disque (`.pluxy_library.json`) : au redémarrage la liste
est disponible immédiatement, puis un nouveau scan peut être lancé en tâche de fond.
"""
from __future__ import annotations

import hashlib
import json
import threading
from pathlib import Path
from typing import Dict, List

from .config import ConfigManager
from .models import MediaItem
from .probe import probe


class Library:
    def __init__(self, cfgm: ConfigManager, data_dir: Path, legacy_dir: Path | None = None):
        self.cfgm = cfgm
        self.index_path = data_dir / ".pluxy_library.json"
        # Migration : récupère l'ancien index s'il existe et que le nouveau non.
        if legacy_dir and not self.index_path.exists():
            legacy = legacy_dir / ".pluxy_library.json"
            if legacy.exists():
                try:
                    self.index_path.write_text(legacy.read_text(encoding="utf-8"), encoding="utf-8")
                except Exception:
                    pass
        self._items: Dict[str, MediaItem] = {}
        self._lock = threading.RLock()
        self._scanning = False
        self._load_index()

    @staticmethod
    def make_id(path: str) -> str:
        return hashlib.sha1(path.encode("utf-8")).hexdigest()[:16]

    def get(self, item_id: str) -> MediaItem | None:
        with self._lock:
            return self._items.get(item_id)

    def all(self) -> List[MediaItem]:
        with self._lock:
            return sorted(self._items.values(), key=lambda i: i.title.lower())

    @property
    def is_scanning(self) -> bool:
        return self._scanning

    # -- Persistance ------------------------------------------------------- #
    def _load_index(self) -> None:
        if self.index_path.exists():
            try:
                data = json.loads(self.index_path.read_text(encoding="utf-8"))
                items = {d["id"]: MediaItem.model_validate(d) for d in data}
                with self._lock:
                    self._items = items
            except Exception:
                pass

    def _save_index(self) -> None:
        try:
            with self._lock:
                payload = [i.model_dump() for i in self._items.values()]
            self.index_path.write_text(
                json.dumps(payload, ensure_ascii=False), encoding="utf-8"
            )
        except Exception:
            pass

    # -- Scan -------------------------------------------------------------- #
    def scan(self) -> int:
        """(Re)construit l'index et le persiste. Retourne le nombre d'éléments."""
        cfg = self.cfgm.cfg
        exts = {e.lower() for e in cfg.server.scan_extensions}
        sub_exts = {e.lower() for e in cfg.subtitles.external_extensions}
        found: Dict[str, MediaItem] = {}
        self._scanning = True
        try:
            for root in cfg.server.media_dirs:
                base = Path(root)
                if not base.exists():
                    continue
                for f in base.rglob("*"):
                    if not f.is_file() or f.suffix.lower() not in exts:
                        continue
                    item_id = self.make_id(str(f))
                    # Réutilise l'entrée existante si déjà connue (évite un re-probe).
                    existing = self._items.get(item_id)
                    if existing and existing.size == _safe_size(f):
                        found[item_id] = existing
                        continue
                    try:
                        info = probe(cfg.ffmpeg.ffprobe_path, str(f))
                    except Exception:
                        # ffprobe absent/illisible : entrée minimale (Direct Play possible).
                        found[item_id] = MediaItem(
                            id=item_id, title=f.stem, path=str(f),
                            container=f.suffix.lstrip(".").lower(),
                            size=_safe_size(f), duration=0.0,
                            external_subs=[str(s) for s in self._sidecar_subs(f, sub_exts)],
                        )
                        continue

                    v = info.video
                    subs = self._sidecar_subs(f, sub_exts)
                    found[item_id] = MediaItem(
                        id=item_id, title=f.stem, path=str(f),
                        container=info.container, size=info.size, duration=info.duration,
                        width=v.width if v else None, height=v.height if v else None,
                        video_codec=v.codec if v else None, is_hdr=v.is_hdr if v else False,
                        external_subs=[str(s) for s in subs],
                    )

            with self._lock:
                self._items = found
            self._save_index()
            return len(found)
        finally:
            self._scanning = False

    @staticmethod
    def _sidecar_subs(media: Path, sub_exts: set[str]) -> List[Path]:
        out: List[Path] = []
        stem = media.stem.lower()
        try:
            for sib in media.parent.iterdir():
                if (sib.is_file() and sib.suffix.lower() in sub_exts
                        and sib.stem.lower().startswith(stem)):
                    out.append(sib)
        except Exception:
            pass
        return out


def _safe_size(p: Path) -> int:
    try:
        return p.stat().st_size
    except Exception:
        return 0
