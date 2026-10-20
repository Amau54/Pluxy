"""Vues publiques du catalogue et pagination des résultats en mémoire."""
from __future__ import annotations

import hashlib
import threading
import unicodedata
from collections import Counter, OrderedDict
from dataclasses import dataclass

from .models import MediaItem, MovieMetadata


def folded(value: str | None) -> str:
    return "".join(char for char in unicodedata.normalize("NFKD", value or "")
                   if not unicodedata.combining(char)).casefold()


def resolution(item: MediaItem) -> str | None:
    if item.kind not in {"movie", "series", "clip"}:
        return None
    height, width = item.height or 0, item.width or 0
    if height >= 2139 or width >= 3802:
        return "4k"
    if height >= 1070 or width >= 1901:
        return "1080p"
    if height >= 713 or width >= 1268:
        return "720p"
    return "sd" if height > 0 or width > 0 else None


def public_item(item: MediaItem, meta: MovieMetadata | None, watch: dict) -> MediaItem:
    pub = item.model_copy()
    pub.path = ""
    pub.fingerprint = ""
    pub.external_subs = []
    if meta and item.kind in {"movie", "series"}:
        pub.year, pub.poster_url = meta.year or item.year, meta.poster_url
        pub.has_metadata, pub.genres = meta.matched, list(meta.genres)
        pub.metadata_title = meta.title if meta.matched else None
        pub.release_date = meta.release_date
        pub.episode_still_url = meta.episode_still_url if item.kind == "series" else None
    if pub.kind in {"movie", "series", "clip", "photo", "audio"}:
        pub.thumbnail_url = f"/api/library/items/{item.id}/thumbnail?v=2"
    position = int(watch.get("position_ms", 0) or 0)
    duration = int(watch.get("duration_ms", 0) or 0)
    pub.watched = bool(watch.get("watched"))
    pub.in_progress = not pub.watched and position > 0
    pub.progress = min(1, position / duration) if pub.in_progress and duration > 0 else 0
    pub.last_played_at = float(watch.get("last_played_at", 0) or 0)
    return pub


@dataclass(frozen=True)
class Query:
    kind: str = "all"
    q: str = ""
    watched: str = "all"
    genres: tuple[str, ...] = ()
    year: int | None = None
    duration_max_minutes: int | None = None
    resolution: str = "all"
    hdr_only: bool = False
    sort: str = "title"
    descending: bool = False
    title_prefix: str = ""
    grouped: bool = False
    group_id: str = ""
    audio_folders: bool = False


@dataclass(frozen=True)
class Row:
    item: MediaItem
    search: str
    title: str
    genres: frozenset[str]
    resolution: str | None


class Catalogue:
    def __init__(self, library, metadata, watch):
        self.library, self.metadata, self.watch = library, metadata, watch
        self._lock = threading.RLock()
        self._version = None
        self._revision = ""
        self._rows: tuple[Row, ...] = ()
        self._queries: OrderedDict[Query, tuple[MediaItem, ...]] = OrderedDict()
        self._source_revision = None
        self._source_ids: frozenset[str] = frozenset()

    def _snapshot(self) -> tuple[str, tuple[Row, ...]]:
        scan_revision, items = self.library.snapshot()
        with self._lock:
            if scan_revision != self._source_revision:
                self._source_ids = frozenset(item.id for item in items)
                self._source_revision = scan_revision
            item_ids = self._source_ids
        meta_revision, metadata = self.metadata.snapshot(item_ids)
        watch_revision, watch = self.watch.snapshot()
        version = (scan_revision, meta_revision, watch_revision)
        with self._lock:
            if self._version != version:
                rows = []
                for item in items:
                    meta = metadata.get(item.id)
                    pub = public_item(item, meta, watch.get(item.id, {}))
                    search = folded(" ".join(filter(None, [item.title, item.series_name, item.artist,
                                                           item.album, item.folder, pub.metadata_title])))
                    rows.append(Row(pub, search, folded(pub.metadata_title or item.title),
                                    frozenset(folded(value) for value in pub.genres), resolution(pub)))
                self._revision = hashlib.sha256(repr(version).encode()).hexdigest()[:24]
                self._rows, self._version = tuple(rows), version
                self._queries.clear()
            return self._revision, self._rows

    @staticmethod
    def group_key(item: MediaItem, audio_folders: bool = False) -> tuple:
        if item.kind == "series":
            return (item.kind, item.series_key or folded(item.series_name or item.title))
        if item.kind == "audio" and not audio_folders:
            return (item.kind, folded(item.artist), folded(item.album or item.source_name))
        if item.kind in {"photo", "audio"}:
            path = (item.relative_path or "").replace("\\", "/")
            return (item.kind, item.source_id or item.source_name, path.rpartition("/")[0])
        return (item.kind, item.id)

    @staticmethod
    def _matches(row: Row, query: Query) -> bool:
        item = row.item
        if query.title_prefix and not row.title.startswith(query.title_prefix):
            return False
        if query.kind != "all" and (
            (query.kind == "video" and item.kind not in {"movie", "series", "clip"})
            or (query.kind != "video" and item.kind != query.kind)
        ):
            return False
        if query.q and not all(word in row.search for word in folded(query.q).split()):
            return False
        if query.watched == "watched" and not item.watched:
            return False
        if query.watched == "unwatched" and item.watched:
            return False
        if query.watched == "in_progress" and not item.in_progress:
            return False
        if query.genres and not row.genres.intersection(query.genres):
            return False
        if query.year is not None and item.year != query.year:
            return False
        if query.duration_max_minutes is not None and not 0 < item.duration <= query.duration_max_minutes * 60:
            return False
        if query.resolution != "all" and row.resolution != query.resolution:
            return False
        return not query.hdr_only or item.is_hdr

    def page(self, query: Query, offset: int, limit: int, revision: str | None = None):
        current, rows = self._snapshot()
        if revision is not None and revision != current:
            raise ValueError("Le catalogue a changé")
        # Normalisation de la clé réduit les doublons du petit cache de filtres.
        query = Query(**{**query.__dict__, "q": folded(query.q).strip(), "title_prefix": folded(query.title_prefix),
                         "genres": tuple(sorted({folded(value).strip() for value in query.genres if value.strip()}))})
        with self._lock:
            values = self._queries.get(query) if current == self._revision else None
            if values is None:
                candidates = [row for row in rows if self._matches(row, query)]
                if query.group_id:
                    seed = next((row.item for row in rows if row.item.id == query.group_id), None)
                    candidates = [row for row in candidates if seed is not None and self.group_key(row.item, query.audio_folders) == self.group_key(seed, query.audio_folders)]
                def key(row):
                    item = row.item
                    return {"title": row.title, "added": item.added_at,
                            "year": item.release_date or (f"{item.year:04d}-01-01" if item.year else ""),
                            "duration": item.duration}[query.sort]
                # id tranche les égalités : les frontières des pages sont stables.
                candidates.sort(key=lambda row: (row.title, row.item.id))
                candidates.sort(key=key, reverse=query.descending)
                if query.sort in {"year", "duration"}:
                    candidates.sort(key=lambda row: not key(row))
                if query.kind == "movie":
                    candidates.sort(key=lambda row: row.item.watched)
                values = tuple(row.item for row in candidates)
                if query.grouped:
                    groups = {}
                    for item in values:
                        groups.setdefault(self.group_key(item, query.audio_folders), []).append(item)
                    values = tuple(group[0].model_copy(update={"group_count": len(group),
                        "season_count": len({item.season or 0 for item in group}) if group[0].kind == "series" else 0})
                        for group in groups.values())
                if current == self._revision:
                    self._queries[query] = values
                    self._queries.move_to_end(query)
                    while len(self._queries) > 32:
                        self._queries.popitem(last=False)
            else:
                self._queries.move_to_end(query)
        return list(values[offset:offset + limit]), len(values), current

    def facets(self, kind: str = "all") -> dict:
        current, rows = self._snapshot()
        selected = [row for row in rows if self._matches(row, Query(kind=kind))]
        counts = Counter(row.item.kind for row in rows)
        return {
            "genres": sorted({genre for row in selected for genre in row.item.genres}, key=folded),
            "years": sorted({row.item.year for row in selected if row.item.year}, reverse=True),
            "resolutions": [value for value in ("4k", "1080p", "720p", "sd")
                            if any(row.resolution == value for row in selected)],
            "total": len(selected), "revision": current, "query_supported": True,
            "counts": {kind: counts[kind] for kind in ("movie", "series", "clip", "photo", "audio")},
        }

    def home(self) -> dict:
        current, rows = self._snapshot()
        videos = [row.item for row in rows if row.item.kind in {"movie", "series", "clip"}]
        movies = [item for item in videos if item.kind == "movie"]
        selected = {item.id for item in sorted(movies, key=lambda item: (-item.added_at, item.id))[:20]}
        selected.update(item.id for item in sorted((item for item in movies if item.in_progress),
                                                   key=lambda item: (-item.last_played_at, item.id))[:12])
        series: dict[str, list[MediaItem]] = {}
        for item in videos:
            if item.kind == "series":
                series.setdefault(item.series_key or item.series_name or item.id, []).append(item)
        recent = sorted(series, key=lambda key: (-max(item.added_at for item in series[key]), key))[:20]
        started = sorted((key for key, episodes in series.items()
                          if any(item.in_progress for item in episodes)
                          or (any(item.watched for item in episodes) and not all(item.watched for item in episodes))),
                         key=lambda key: (-max(item.last_played_at for item in series[key]), key))[:12]
        selected.update(item.id for key in set(recent + started) for item in series[key])
        counts = Counter(row.item.kind for row in rows)
        return {"items": [item for item in videos if item.id in selected], "revision": current,
                "counts": {kind: counts[kind] for kind in ("movie", "series", "clip", "photo", "audio")}}
