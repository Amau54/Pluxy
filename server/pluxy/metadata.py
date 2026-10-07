"""
Module de métadonnées façon Plex (provider TMDB).

Pipeline :
  1. Nettoyage du nom de fichier  -> (titre, année)   [parse_filename]
  2. Recherche TMDB + détails (casting, genres, bande-annonce)  [_fetch_tmdb]
  3. Cache disque JSON par média  [get / refresh]

Sans clé API TMDB, le module renvoie des métadonnées minimales déduites du nom.
"""
from __future__ import annotations

import difflib
import hashlib
import json
import re
import threading
import time
import uuid
from collections import OrderedDict
from weakref import WeakValueDictionary
from pathlib import Path
from typing import Optional

import httpx

from .config import ConfigManager
from .filename import parse as parse_name
from .models import CastMember, MediaItem, MovieMetadata, Rating

TMDB_API = "https://api.themoviedb.org/3"
IMG = "https://image.tmdb.org/t/p"
OMDB_API = "https://www.omdbapi.com/"
# Normalisation des noms de sources OMDb -> libellés courts.
_OMDB_SOURCES = {
    "Internet Movie Database": "IMDb",
    "Rotten Tomatoes": "Rotten Tomatoes",
    "Metacritic": "Metacritic",
}


def _norm(s: Optional[str]) -> str:
    return re.sub(r"[^a-z0-9]+", "", (s or "").lower())


def _confident(query: str, *candidates: Optional[str]) -> bool:
    """Vrai si AU MOINS un titre candidat ressemble assez à la requête. Évite d'attribuer
    une affiche ARBITRAIRE quand TMDB renvoie un résultat sans rapport (cause des
    « affiches de films sur des épisodes »). Un titre vide/garbled -> aucune affiche."""
    q = _norm(query)
    if not q:
        return False
    for c in candidates:
        cn = _norm(c)
        if not cn:
            continue
        if q in cn or cn in q:
            return True
        if difflib.SequenceMatcher(None, q, cn).ratio() >= 0.55:
            return True
    return False


class MetadataProvider:
    def __init__(self, cfgm: ConfigManager, base_dir: Path):
        self.cfgm = cfgm
        self.meta_dir = base_dir / ".pluxy_meta"
        self.meta_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        # Cache SÉRIE (par clé canonique) : une seule recherche TMDB TV pour TOUS les
        # épisodes d'une série -> évite N appels identiques (quota/rate-limit).
        self._tv_cache: dict[str, MovieMetadata] = {}
        self._memory: OrderedDict[str, tuple[int, dict]] = OrderedDict()
        self._key_locks = WeakValueDictionary()
        self._catalogue: dict[str, MovieMetadata] | None = None
        self._catalogue_ids: frozenset[str] | None = None
        self._revision = 0
        self._epoch = uuid.uuid4().hex

    @staticmethod
    def _summary(meta: dict | MovieMetadata) -> MovieMetadata:
        value = meta.model_dump() if isinstance(meta, MovieMetadata) else meta
        return MovieMetadata.model_validate({key: value[key] for key in
                                            ("title", "year", "poster_url", "matched", "genres", "source")
                                            if key in value})

    def snapshot(self, item_ids: frozenset[str] | None = None) -> tuple[str, dict[str, MovieMetadata]]:
        """Chargement groupé initial, puis zéro accès disque pour les listes."""
        with self._lock:
            if self._catalogue is None or (item_ids is not None and item_ids != self._catalogue_ids):
                values = {item_id: meta for item_id, meta in (self._catalogue or {}).items()
                          if item_ids is None or item_id in item_ids}
                for path in self.meta_dir.glob("*.json"):
                    if len(values) >= 10_000:
                        break
                    if path.stem in values or (item_ids is not None and path.stem not in item_ids):
                        continue
                    try:
                        record = json.loads(path.read_text(encoding="utf-8"))
                        values[path.stem] = self._summary(record.get("metadata", record))
                    except (OSError, ValueError, TypeError, AttributeError):
                        continue
                self._catalogue = values
                self._catalogue_ids = item_ids
            return f"{self._epoch}-{self._revision}", self._catalogue

    # -- Cache ------------------------------------------------------------- #
    def _cache_path(self, item_id: str) -> Path:
        return self.meta_dir / f"{item_id}.json"

    def clear_tv_cache(self) -> None:
        """Vide le cache série en mémoire (avant un rafraîchissement forcé)."""
        with self._lock:
            self._tv_cache.clear()

    def cached(self, item_id: str) -> Optional[MovieMetadata]:
        record = self._record(item_id)
        if record is None:
            return None
        try:
            return MovieMetadata.model_validate(record.get("metadata", record))
        except ValueError:
            return None

    def _record(self, item_id: str) -> dict | None:
        p = self._cache_path(item_id)
        try:
            stamp = p.stat().st_mtime_ns
            with self._lock:
                cached = self._memory.get(item_id)
                if cached is not None and cached[0] == stamp:
                    self._memory.move_to_end(item_id)
                    return cached[1]
            record = json.loads(p.read_text(encoding="utf-8"))
            if not isinstance(record, dict):
                return None
            with self._lock:
                self._memory[item_id] = (stamp, record)
                while len(self._memory) > 512:
                    self._memory.popitem(last=False)
            return record
        except (OSError, ValueError):
            return None

    def _save(self, item_id: str, meta: MovieMetadata, fingerprint: str = "") -> None:
        from .paths import atomic_write_text
        record = {"metadata": meta.model_dump(), "fingerprint": fingerprint,
                  "retry_at": time.time() + 60 if not meta.matched else 0}
        atomic_write_text(self._cache_path(item_id), json.dumps(record, ensure_ascii=False))
        with self._lock:
            # Copier permet aux lecteurs de terminer sur un instantané cohérent.
            if self._catalogue is not None:
                self._catalogue = {**self._catalogue, item_id: self._summary(meta)}
            self._memory.pop(item_id, None)
            self._revision += 1

    # -- API publique ------------------------------------------------------ #
    def get(self, item: MediaItem, force: bool = False, share_series: bool = False) -> MovieMetadata:
        with self._lock:
            lock = self._key_locks.setdefault(item.id, threading.Lock())
        with lock:
            return self._get(item, force, share_series)

    def _get(self, item: MediaItem, force: bool = False, share_series: bool = False) -> MovieMetadata:
        cfg = self.cfgm.cfg.metadata
        fingerprint = hashlib.sha256(json.dumps([
            cfg.model_dump(), item.title, item.series_key, item.modified_at, item.size,
        ], sort_keys=True).encode()).hexdigest()
        is_series = item.kind == "series" and bool(item.series_name)

        if not force:
            c = self.cached(item.id)
            record = self._record(item.id)
            # Cache PÉRIMÉ si une SÉRIE a été résolue par erreur comme un FILM (ancienne
            # version) : on re-cherche en TV (corrige les « affiches de films sur séries »).
            if (c is not None and record is not None and record.get("fingerprint") == fingerprint
                    and (c.matched or record.get("retry_at", 0) > time.time())
                    and not (is_series and c.source == "tmdb" and c.matched)):
                return c

        if is_series:
            title, year = item.series_name or item.title, None
        else:
            parsed = parse_name(item.title)
            title, year = parsed.title, parsed.year

        meta: Optional[MovieMetadata] = None
        if cfg.enabled and cfg.tmdb_api_key:
            try:
                if is_series:
                    # Une seule recherche TV par SÉRIE (clé canonique) -> partagée.
                    config_key = hashlib.sha256(json.dumps(cfg.model_dump(), sort_keys=True).encode()).hexdigest()
                    skey = f"{item.series_key or _norm(title)}:{config_key}"
                    with self._lock:
                        series_lock = self._key_locks.setdefault("series:" + skey, threading.Lock())
                    with series_lock:
                        with self._lock:
                            shared = self._tv_cache.get(skey) if not force or share_series else None
                        if shared is not None:
                            meta = shared
                        else:
                            meta = self._fetch_tmdb_tv(title, cfg.tmdb_api_key, cfg.language, cfg.omdb_api_key)
                            if meta is not None and meta.matched:
                                with self._lock:
                                    self._tv_cache[skey] = meta
                                    while len(self._tv_cache) > 512:
                                        self._tv_cache.pop(next(iter(self._tv_cache)))
                else:
                    meta = self._fetch_tmdb(title, year, cfg.tmdb_api_key, cfg.language, cfg.omdb_api_key)
            except Exception:
                meta = None

        if meta is None:                       # repli : titre/année déduits
            meta = MovieMetadata(title=title, year=year, matched=False, source="filename")

        self._save(item.id, meta, fingerprint)
        return meta

    # -- Notes multi-sites (OMDb : IMDb / Rotten Tomatoes / Metacritic) ----- #
    def _ratings(self, tmdb_vote, imdb_id: Optional[str], omdb_key: str) -> list[Rating]:
        out: list[Rating] = []
        if tmdb_vote:
            out.append(Rating(source="TMDB", value=f"{round(tmdb_vote, 1)}/10"))
        if imdb_id and omdb_key:
            try:
                with httpx.Client(timeout=10.0) as cli:
                    r = cli.get(OMDB_API, params={"i": imdb_id, "apikey": omdb_key})
                    r.raise_for_status()
                    j = r.json()
                if j.get("Response") == "True":
                    for rt in j.get("Ratings", []):
                        src = _OMDB_SOURCES.get(rt.get("Source", ""))
                        val = rt.get("Value", "")
                        if src and val:
                            out.append(Rating(source=src, value=val))
            except Exception:
                pass
        return out

    # -- TMDB séries (TV) -------------------------------------------------- #
    def _fetch_tmdb_tv(self, name: str, key: str, lang: str,
                       omdb_key: str = "") -> Optional[MovieMetadata]:
        """Recherche une SÉRIE sur TMDB (/search/tv) et renvoie l'affiche + infos show."""
        with httpx.Client(timeout=12.0) as cli:
            r = cli.get(f"{TMDB_API}/search/tv",
                        params={"api_key": key, "query": name, "language": lang})
            r.raise_for_status()
            results = r.json().get("results", [])
            if not results or not _confident(
                name, results[0].get("name"), results[0].get("original_name")
            ):
                return MovieMetadata(title=name, matched=False)   # pas d'affiche arbitraire
            tid = results[0]["id"]
            d = cli.get(f"{TMDB_API}/tv/{tid}",
                        params={"api_key": key, "language": lang,
                                "append_to_response": "credits,videos,external_ids"})
            d.raise_for_status()
            j = d.json()
        imdb_id = (j.get("external_ids") or {}).get("imdb_id")

        credits = j.get("credits", {})
        cast = [
            CastMember(
                name=c.get("name", ""),
                character=c.get("character") or None,
                profile_url=(f"{IMG}/w185{c['profile_path']}" if c.get("profile_path") else None),
            )
            for c in credits.get("cast", [])[:10]
        ]
        creator = next((c.get("name") for c in j.get("created_by", [])), None)
        vids = j.get("videos", {}).get("results", [])
        yt = next((v for v in vids if v.get("site") == "YouTube" and v.get("type") == "Trailer"),
                  next((v for v in vids if v.get("site") == "YouTube"), None))
        yt_key = yt.get("key") if yt else None
        fa = j.get("first_air_date") or ""
        return MovieMetadata(
            tmdb_id=tid,
            title=j.get("name") or name,
            original_title=j.get("original_name"),
            year=int(fa[:4]) if fa[:4].isdigit() else None,
            overview=j.get("overview") or None,
            tagline=j.get("tagline") or None,
            genres=[g["name"] for g in j.get("genres", [])],
            runtime=(j.get("episode_run_time") or [None])[0],
            rating=round(j.get("vote_average"), 1) if j.get("vote_average") else None,
            ratings=self._ratings(j.get("vote_average"), imdb_id, omdb_key),
            poster_url=f"{IMG}/w500{j['poster_path']}" if j.get("poster_path") else None,
            backdrop_url=f"{IMG}/w1280{j['backdrop_path']}" if j.get("backdrop_path") else None,
            cast=cast,
            director=creator,
            trailer_youtube_key=yt_key,
            trailer_url=f"https://www.youtube.com/watch?v={yt_key}" if yt_key else None,
            matched=True,
            source="tmdb_tv",
        )

    # -- TMDB films -------------------------------------------------------- #
    def _fetch_tmdb(self, title: str, year: Optional[int],
                    key: str, lang: str, omdb_key: str = "") -> Optional[MovieMetadata]:
        with httpx.Client(timeout=12.0) as cli:
            def _search(with_year: bool):
                params = {"api_key": key, "query": title, "language": lang}
                if with_year and year:
                    params["primary_release_year"] = year
                r = cli.get(f"{TMDB_API}/search/movie", params=params)
                r.raise_for_status()
                return r.json().get("results", [])

            # 1) recherche avec année (filtre fort), 2) repli sans année.
            results = _search(with_year=True)
            if not results:
                results = _search(with_year=False)
            if not results:
                return MovieMetadata(title=title, year=year, matched=False)

            # Si une année est connue, privilégier un résultat à year ±1
            # (décalages de sortie régionale), sinon le 1er (mieux noté).
            best = results[0]
            if year:
                for c in results:
                    rd = (c.get("release_date") or "")[:4]
                    if rd.isdigit() and abs(int(rd) - year) <= 1:
                        best = c
                        break
            if not _confident(title, best.get("title"), best.get("original_title")):
                return MovieMetadata(title=title, year=year, matched=False)  # évite un mauvais film
            mid = best["id"]
            d = cli.get(
                f"{TMDB_API}/movie/{mid}",
                params={"api_key": key, "language": lang,
                        "append_to_response": "credits,videos"},
            )
            d.raise_for_status()
            j = d.json()

        # Casting (10 premiers) + réalisateur
        credits = j.get("credits", {})
        cast = [
            CastMember(
                name=c.get("name", ""),
                character=c.get("character") or None,
                profile_url=(f"{IMG}/w185{c['profile_path']}" if c.get("profile_path") else None),
            )
            for c in credits.get("cast", [])[:10]
        ]
        director = next(
            (c["name"] for c in credits.get("crew", []) if c.get("job") == "Director"),
            None,
        )

        # Bande-annonce YouTube (priorité Trailer officiel)
        vids = j.get("videos", {}).get("results", [])
        yt = next((v for v in vids if v.get("site") == "YouTube" and v.get("type") == "Trailer"),
                  next((v for v in vids if v.get("site") == "YouTube"), None))
        yt_key = yt.get("key") if yt else None

        rd = j.get("release_date") or ""
        return MovieMetadata(
            tmdb_id=mid,
            title=j.get("title") or title,
            original_title=j.get("original_title"),
            year=int(rd[:4]) if rd[:4].isdigit() else year,
            overview=j.get("overview") or None,
            tagline=j.get("tagline") or None,
            genres=[g["name"] for g in j.get("genres", [])],
            runtime=j.get("runtime") or None,
            rating=round(j.get("vote_average"), 1) if j.get("vote_average") else None,
            ratings=self._ratings(j.get("vote_average"), j.get("imdb_id"), omdb_key),
            poster_url=f"{IMG}/w500{j['poster_path']}" if j.get("poster_path") else None,
            backdrop_url=f"{IMG}/w1280{j['backdrop_path']}" if j.get("backdrop_path") else None,
            cast=cast,
            director=director,
            trailer_youtube_key=yt_key,
            trailer_url=f"https://www.youtube.com/watch?v={yt_key}" if yt_key else None,
            matched=True,
            source="tmdb",
        )
