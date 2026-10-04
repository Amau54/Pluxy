"""Scanner de bibliothèque : indexe les fichiers média + sous-titres externes.

L'index est PERSISTÉ sur disque (`.pluxy_library.json`) : au redémarrage la liste
est disponible immédiatement, puis un nouveau scan peut être lancé en tâche de fond.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat as _stat
import threading
import time
import unicodedata
from pathlib import Path
from typing import Dict, List

from .config import ConfigManager
from . import filename as _fn
from .models import MediaItem, SubtitleTrack
from .probe import probe

# Motif d'épisode de SÉRIE dans un nom de fichier : S01E02, 1x02, "saison 1", "episode 3"…
_SERIES_RE = re.compile(
    r"(s\d{1,2}[\s._-]?e\d{1,2}|\b\d{1,2}x\d{2}\b|saison[\s._-]?\d|season[\s._-]?\d|episode[\s._-]?\d)",
    re.IGNORECASE,
)
# Motif saison+épisode CAPTURÉ (S01E02 ou 1x02) pour extraire les numéros.
_EP_RE = re.compile(r"(?:s(\d{1,2})[\s._-]?e(\d{1,2})|\b(\d{1,2})x(\d{2})\b)", re.IGNORECASE)
# Dossier de saison (« Season 1 », « Saison 02 »…).
_SEASON_DIR_RE = re.compile(r"^(?:season|saison|s)[\s._-]?\d+$", re.IGNORECASE)
# Dossiers qui désignent une bibliothèque de séries.
_SERIES_DIRS = {"series", "série", "séries", "serie", "tv", "shows", "tvshows", "tv shows"}

# Codes langue ISO courants reconnus dans les noms de sous-titres sidecar.
_LANG_CODES = {
    "fr", "fre", "fra", "en", "eng", "es", "spa", "de", "ger", "deu",
    "it", "ita", "pt", "por", "nl", "dut", "ru", "rus", "ja", "jpn",
    "ko", "kor", "zh", "chi", "ar", "ara", "vff", "vfq", "vostfr",
}

MAX_MEDIA_ITEMS = 10_000


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
        self.scan_status = {"status": "idle", "error": None, "finished_at": None}
        self._revision = ""
        self._sorted_items: list[MediaItem] = []
        self._load_index()
        self._refresh_snapshot()

    @staticmethod
    def make_id(path: str) -> str:
        return hashlib.sha1(path.encode("utf-8")).hexdigest()[:16]

    def get(self, item_id: str) -> MediaItem | None:
        with self._lock:
            return self._items.get(item_id)

    def all(self) -> List[MediaItem]:
        with self._lock:
            return list(self._sorted_items)

    def _refresh_snapshot(self) -> None:
        with self._lock:
            self._sorted_items = sorted(self._items.values(), key=lambda item: item.title.lower())
            payload = [item.model_dump() for item in self._sorted_items]
            self._revision = hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()[:24]

    def page(self, offset: int, limit: int, revision: str | None = None):
        with self._lock:
            if revision is not None and revision != self._revision:
                raise ValueError("Le catalogue a change")
            return list(self._sorted_items[offset:offset + limit]), len(self._sorted_items), self._revision

    @property
    def is_scanning(self) -> bool:
        with self._lock:
            return self._scanning

    # -- Persistance ------------------------------------------------------- #
    def _load_index(self) -> None:
        from .config import _read_config_text
        try:
            data = json.loads(_read_config_text(self.index_path))
        except FileNotFoundError:
            return
        except (ValueError, TypeError):
            shutil.copy2(self.index_path, self.index_path.with_name(f"{self.index_path.name}.invalid-{time.time_ns()}"))
            return
        if isinstance(data, list):
            try:
                items: dict[str, MediaItem] = {}
                for value in data[:MAX_MEDIA_ITEMS] if isinstance(data, list) else []:
                    try:
                        item = MediaItem.model_validate(value)
                        items[item.id] = item
                    except Exception:
                        # Une entree corrompue ne doit pas effacer tout le catalogue.
                        continue
                with self._lock:
                    self._items = items
            except (TypeError, ValueError):
                return

    def _save_index(self, items: dict[str, MediaItem] | None = None) -> None:
        from .paths import atomic_write_text
        with self._lock:
            payload = [i.model_dump() for i in (self._items if items is None else items).values()]
        atomic_write_text(self.index_path, json.dumps(payload, ensure_ascii=False))

    # -- Scan -------------------------------------------------------------- #
    def scan(self, stop_event: threading.Event | None = None) -> int:
        """(Re)construit l'index et le persiste. Retourne le nombre d'éléments."""
        # Garde de ré-entrance : un seul scan à la fois (évite la corruption d'index
        # et les écritures entrelacées de .pluxy_library.json).
        with self._lock:
            if self._scanning:
                return len(self._items)
            self._scanning = True
            self.scan_status = {"status": "scanning", "error": None, "finished_at": None}

        cfg = self.cfgm.cfg
        exts = {e.lower() for e in cfg.server.scan_extensions}
        sub_exts = {e.lower() for e in cfg.subtitles.external_extensions}
        found: Dict[str, MediaItem] = {}
        with self._lock:
            previous = {item_id: item.model_copy(deep=True) for item_id, item in self._items.items()}
        missing_roots: list[Path] = []
        try:
            for root in cfg.server.media_dirs:
                if stop_event is not None and stop_event.is_set():
                    return len(previous)
                base = Path(root)
                if not base.is_dir():
                    missing_roots.append(base)
                    continue
                # Une seule énumération par dossier, sous-titres compris. rglob
                # suivi d'un iterdir pour chaque vidéo était quadratique dans un
                # gros dossier. onerror conserve aussi les sous-dossiers NAS/USB
                # devenus inaccessibles pendant le scan.
                def inaccessible(error: OSError) -> None:
                    missing_roots.append(Path(error.filename) if error.filename else base)

                for directory, _, names in os.walk(base, onerror=inaccessible):
                    if stop_event is not None and stop_event.is_set():
                        return len(previous)
                    if len(found) >= MAX_MEDIA_ITEMS:
                        break
                    parent = Path(directory)
                    sidecars = sorted(
                        (parent / name for name in names
                         if Path(name).suffix.lower() in sub_exts and (parent / name).is_file()),
                        key=lambda path: path.name.casefold(),
                    )
                    sidecar_index: dict[str, list[Path]] = {}
                    for subtitle in sidecars:
                        prefix = subtitle.stem.lower()
                        while prefix:
                            sidecar_index.setdefault(prefix, []).append(subtitle)
                            prefix = prefix.rpartition(".")[0]
                    for name in names:
                        if stop_event is not None and stop_event.is_set():
                            return len(previous)
                        if len(found) >= MAX_MEDIA_ITEMS:
                            break
                        f = parent / name
                        if f.suffix.lower() not in exts:
                            continue
                        item_id = self.make_id(str(f))
                        try:
                            stat = f.stat()
                        except OSError:
                            missing_roots.append(f)
                            continue
                        if not _stat.S_ISREG(stat.st_mode):
                            continue
                        kind = _detect_kind(f)
                        added_at = stat.st_mtime
                        sname, skey, season, episode = (
                            _parse_series(f) if kind == "series" else (None, None, None, None)
                        )
                        subs = self._sidecar_subs(f, sub_exts, sidecar_index.get(f.stem.lower(), []))
                        sub_tracks = self._sub_tracks(f, subs)
                        # Taille ET date de modification : un fichier remplacé à
                        # taille identique doit être réanalysé.
                        existing = previous.get(item_id)
                        if (existing and existing.size == stat.st_size and existing.added_at == added_at
                                and ((existing.probe_complete and existing.duration > 0)
                                     or existing.probe_retry_at > time.time())):
                            found[item_id] = existing.model_copy(update={
                                "kind": kind,
                                "series_name": sname,
                                "series_key": skey,
                                "season": season,
                                "episode": episode,
                                "external_subs": [str(s) for s in subs],
                                "subtitles": sub_tracks,
                            })
                            continue
                        try:
                            info = probe(cfg.ffmpeg.ffprobe_path, str(f))
                        except Exception:
                            # ffprobe absent/illisible : entrée minimale (Direct Play possible).
                            found[item_id] = MediaItem(
                                id=item_id, title=f.stem, path=str(f),
                                container=f.suffix.lstrip(".").lower(),
                                size=stat.st_size, duration=0.0, kind=kind, added_at=added_at,
                                series_name=sname, series_key=skey, season=season, episode=episode,
                                external_subs=[str(s) for s in subs], subtitles=sub_tracks,
                                probe_complete=False, probe_retry_at=time.time() + 60,
                            )
                            continue

                        v = info.video
                        found[item_id] = MediaItem(
                            id=item_id, title=f.stem, path=str(f),
                            container=info.container, size=stat.st_size, duration=info.duration,
                            width=v.width if v else None, height=v.height if v else None,
                            video_codec=v.codec if v else None, is_hdr=v.is_hdr if v else False,
                            kind=kind, added_at=added_at,
                            series_name=sname, series_key=skey, season=season, episode=episode,
                            external_subs=[str(s) for s in subs], subtitles=sub_tracks,
                        )
                if len(found) >= MAX_MEDIA_ITEMS:
                    break

            # Un disque USB/NAS temporairement absent ne doit pas effacer son catalogue
            # ni les positions de reprise. Les éléments seront retirés au prochain scan
            # lorsque le dossier est à nouveau accessible et le fichier réellement absent.
            for existing in previous.values():
                if len(found) >= MAX_MEDIA_ITEMS:
                    break
                if existing.id in found:
                    continue
                if any(_is_within(Path(existing.path), root) for root in missing_roots):
                    found[existing.id] = existing

            if stop_event is not None and stop_event.is_set():
                return len(previous)
            self._save_index(found)
            with self._lock:
                self._items = found
                self._refresh_snapshot()
            truncated = len(found) >= MAX_MEDIA_ITEMS
            self.scan_status = {"status": "warning" if missing_roots or truncated else "success", "error": None,
                                "finished_at": time.time(), "unavailable_sources": len(missing_roots),
                                "truncated": truncated}
            return len(found)
        except Exception:
            self.scan_status = {"status": "error", "error": "Analyse impossible", "finished_at": time.time()}
            raise
        finally:
            with self._lock:
                self._scanning = False
                if self.scan_status["status"] == "scanning":
                    self.scan_status = {"status": "cancelled", "error": None, "finished_at": time.time()}

    @staticmethod
    def _sub_tracks(media: Path, subs: List[Path]) -> List[SubtitleTrack]:
        """Décrit chaque sous-titre sidecar : langue (déduite du nom) + format."""
        stem = media.stem.lower()
        out: List[SubtitleTrack] = []
        for i, s in enumerate(subs):
            fmt = s.suffix.lstrip(".").lower() or "srt"
            # "Film.fr.srt" -> suffixe "fr" entre le stem et l'extension.
            extra = s.stem.lower()
            if extra.startswith(stem):
                extra = extra[len(stem):].strip(". ")
            lang = extra if extra in _LANG_CODES else None
            label = (lang.upper() if lang else "Sous-titres") + f" ({fmt})"
            out.append(SubtitleTrack(index=i, lang=lang, format=fmt, label=label))
        return out

    @staticmethod
    def _sidecar_subs(
        media: Path, sub_exts: set[str], candidates: List[Path] | None = None,
    ) -> List[Path]:
        """Sous-titres sidecar : `Film.srt` ou `Film.fr.srt` (convention Plex).

        Appariement STRICT : nom identique OU préfixe suivi d'un point — évite que
        `Film2.srt` ou `Film Part2.srt` soit rattaché par erreur à `Film.mkv`.
        """
        out: List[Path] = []
        stem = media.stem.lower()
        try:
            for sib in candidates if candidates is not None else media.parent.iterdir():
                if sib.suffix.lower() not in sub_exts or (candidates is None and not sib.is_file()):
                    continue
                sub_stem = sib.stem.lower()
                if sub_stem == stem or sub_stem.startswith(stem + "."):
                    out.append(sib)
        except Exception:
            pass
        return sorted(out, key=lambda path: path.name.casefold())


def _safe_size(p: Path) -> int:
    try:
        return p.stat().st_size
    except Exception:
        return 0


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.resolve(strict=False).relative_to(root.resolve(strict=False))
        return True
    except (OSError, ValueError):
        return False


def _safe_mtime(p: Path) -> float:
    try:
        return p.stat().st_mtime
    except Exception:
        return 0.0


def _detect_kind(p: Path) -> str:
    """Déduit film vs série : motif d'épisode (nom OU sous-dossier « Saison 2 »…),
    ou dossier racine « Séries/TV »."""
    if _SERIES_RE.search(p.name):
        return "series"
    if {part.lower() for part in p.parts} & _SERIES_DIRS:
        return "series"
    # Motif saison/épisode ailleurs dans le chemin (sous-dossier "Season 2", "Saison 1"…).
    if _SERIES_RE.search(str(p)):
        return "series"
    return "movie"


# Suffixe « saison/season/SX/partie/intégrale/complete » en fin de nom (dossier ou
# fichier) -> retiré pour que « Breaking Bad Saison 1 » et « Breaking Bad Saison 2 »
# fusionnent. Appliqué en boucle (plusieurs suffixes possibles).
_SEASON_SUFFIX_RE = re.compile(
    r"(?i)[\s._-]+(?:"
    r"(?:saison|season|partie|part|pt|vol(?:ume)?|saga|int[ée]grale|complet[e]?|"
    r"complete|series?|coffret)(?:[\s._-]*\d{1,3})?"
    r"|s\d{1,3}"                       # « SXX » nu exige des chiffres (sinon « Us » -> « U »)
    r")\s*$"
)
# Articles de tête retirés pour la CLÉ canonique uniquement (pas l'affichage).
_ARTICLE_RE = re.compile(r"(?i)^(?:le|la|les|l|the|a|an|un|une|des|der|die|das|el|los)\b[\s']*")
# Numéro d'épisode hors SxxExx : « Episode 3 », « Ep 3 », « E03 », « - 03 - ».
_EP_LOOSE_RE = re.compile(r"(?i)\b(?:episode|épisode|ep|e)[\s._-]*(\d{1,3})\b")
_SEASON_DIR_NUM_RE = re.compile(r"(?i)\b(?:saison|season|s)[\s._-]?(\d{1,3})\b")


def _clean_title(s: str) -> str:
    """Nettoie un nom brut (points/underscores -> espaces, espaces multiples)."""
    s = re.sub(r"[._]+", " ", s)
    return re.sub(r"\s+", " ", s).strip(" -_.")


def _clean_series_name(raw: str) -> str:
    """Nom de série lisible : sépare, RETIRE les tags techniques (résolution, codec,
    source, audio, langue, année, édition…) puis le suffixe de saison."""
    if not raw:
        return ""
    s = _fn._to_spaces(raw)
    for pat in (_fn._YEAR, _fn._RESOLUTION, _fn._SOURCE, _fn._CODEC, _fn._BITDEPTH,
                _fn._HDR, _fn._AUDIO, _fn._LANG, _fn._EDITION, _fn._PROPER):
        s = pat.sub(" ", s)
    s = re.sub(r"\s{2,}", " ", s).strip(" -_.·")
    prev = None
    while prev != s:                       # retire les suffixes de saison empilés
        prev = s
        s = _SEASON_SUFFIX_RE.sub("", s).strip(" -_.·")
    return s


def _key_base(name: str) -> str:
    """Forme ASCII minuscule sans séparateur (sans retirer l'article)."""
    s = "".join(c for c in unicodedata.normalize("NFKD", name) if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", "", s.lower())


def _series_key(name: str) -> str:
    """Clé canonique de regroupement : sans accents, casse, article, ni séparateur."""
    s = "".join(c for c in unicodedata.normalize("NFKD", name) if not unicodedata.combining(c))
    s = _ARTICLE_RE.sub("", s.lower().strip())
    return re.sub(r"[^a-z0-9]+", "", s)


def _raw_series_folder(p: Path) -> str | None:
    """Nom BRUT du 1er dossier « série » (hors dossier de saison et racine Séries/TV)."""
    for parent in p.parents:
        nm = parent.name
        if not nm or nm.lower() in _SERIES_DIRS or _SEASON_DIR_RE.match(nm):
            continue
        return nm
    return None


def _series_from_path(p: Path) -> str | None:
    """Nom de série (NETTOYÉ) déduit de l'arborescence."""
    raw = _raw_series_folder(p)
    if raw:
        cleaned = _clean_series_name(raw)
        if cleaned:
            return cleaned
    return None


def _season_from_path(p: Path) -> int | None:
    for parent in p.parents:
        m = _SEASON_DIR_NUM_RE.fullmatch(parent.name.strip()) or _SEASON_DIR_NUM_RE.search(parent.name)
        if m and parent.name.lower() not in _SERIES_DIRS:
            try:
                return int(m.group(1))
            except (TypeError, ValueError):
                pass
    return None


def _parse_series(p: Path) -> tuple[str, str, int | None, int | None]:
    """(nom affichage, clé canonique, saison, épisode). Robuste aux noms « sales »
    et au même show réparti sur plusieurs dossiers/orthographes."""
    name = p.stem
    m = _EP_RE.search(name)
    season = episode = None
    raw_prefix = None
    if m:
        if m.group(1):
            season, episode = int(m.group(1)), int(m.group(2))
        else:
            season, episode = int(m.group(3)), int(m.group(4))
        raw_prefix = name[: m.start()]

    # Nom d'affichage : DOSSIER de la série (capitalisation propre) en priorité,
    # puis préfixe du fichier nettoyé, puis nom de fichier nettoyé.
    display = _series_from_path(p)
    if not display:
        display = _clean_series_name(raw_prefix) if raw_prefix else ""
    if not display:
        display = _clean_series_name(name) or _clean_title(name) or "Série"

    rawf = _raw_series_folder(p)
    # Nom réduit à un simple article (« The », « La ») -> on préfère le dossier brut
    # (nettoyage léger qui garde « La Saga ») pour l'affichage ET la clé.
    if not _series_key(display) and rawf:
        display = _clean_title(rawf) or display

    # Saison/épisode hors SxxExx (dossier « Saison 2 » + « Episode 3 »).
    if season is None:
        season = _season_from_path(p)
    if episode is None:
        le = _EP_LOOSE_RE.search(name)
        if le:
            episode = int(le.group(1))

    # Clé canonique : depuis le nom d'affichage ; si vide, on NE retombe JAMAIS sur le
    # code d'épisode (sinon des séries différentes fusionnent sur « s01e01 » et une série
    # se disperse). On utilise le DOSSIER brut (en gardant l'article si besoin), puis le
    # préfixe du fichier.
    key = _series_key(display)
    if not key and rawf:
        key = _series_key(rawf) or _key_base(rawf)
    if not key and raw_prefix:
        key = _series_key(raw_prefix) or _key_base(raw_prefix)
    if not key:
        key = _key_base(name) or "serie"
    return display, key, season, episode
