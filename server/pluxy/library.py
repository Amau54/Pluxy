"""Scanner de bibliothèque : indexe les fichiers média + sous-titres externes."""
from __future__ import annotations

import hashlib
import threading
from pathlib import Path
from typing import Dict, List

from .config import ConfigManager
from .models import MediaItem
from .probe import probe


class Library:
    def __init__(self, cfgm: ConfigManager):
        self.cfgm = cfgm
        self._items: Dict[str, MediaItem] = {}
        self._lock = threading.RLock()

    @staticmethod
    def make_id(path: str) -> str:
        return hashlib.sha1(path.encode("utf-8")).hexdigest()[:16]

    def get(self, item_id: str) -> MediaItem | None:
        with self._lock:
            return self._items.get(item_id)

    def all(self) -> List[MediaItem]:
        with self._lock:
            return sorted(self._items.values(), key=lambda i: i.title.lower())

    def scan(self) -> int:
        """(Re)construit l'index. Retourne le nombre d'éléments trouvés."""
        cfg = self.cfgm.cfg
        exts = {e.lower() for e in cfg.server.scan_extensions}
        sub_exts = {e.lower() for e in cfg.subtitles.external_extensions}
        found: Dict[str, MediaItem] = {}

        for root in cfg.server.media_dirs:
            base = Path(root)
            if not base.exists():
                continue
            for f in base.rglob("*"):
                if not f.is_file() or f.suffix.lower() not in exts:
                    continue
                try:
                    info = probe(cfg.ffmpeg.ffprobe_path, str(f))
                except Exception:
                    continue

                v = info.video
                subs = self._sidecar_subs(f, sub_exts)
                item_id = self.make_id(str(f))
                found[item_id] = MediaItem(
                    id=item_id,
                    title=f.stem,
                    path=str(f),
                    container=info.container,
                    size=info.size,
                    duration=info.duration,
                    width=v.width if v else None,
                    height=v.height if v else None,
                    video_codec=v.codec if v else None,
                    is_hdr=v.is_hdr if v else False,
                    external_subs=[str(s) for s in subs],
                )

        with self._lock:
            self._items = found
        return len(found)

    @staticmethod
    def _sidecar_subs(media: Path, sub_exts: set[str]) -> List[Path]:
        """Cherche les .srt etc. partageant le préfixe du fichier (Film.fr.srt)."""
        out: List[Path] = []
        stem = media.stem.lower()
        for sib in media.parent.iterdir():
            if (
                sib.is_file()
                and sib.suffix.lower() in sub_exts
                and sib.stem.lower().startswith(stem)
            ):
                out.append(sib)
        return out
