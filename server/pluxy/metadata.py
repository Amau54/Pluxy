"""
Module de métadonnées façon Plex (provider TMDB).

Pipeline :
  1. Nettoyage du nom de fichier  -> (titre, année)   [parse_filename]
  2. Recherche TMDB + détails (casting, genres, bande-annonce)  [_fetch_tmdb]
  3. Cache disque JSON par média  [get / refresh]

Sans clé API TMDB, le module renvoie des métadonnées minimales déduites du nom.
"""
from __future__ import annotations

import json
import re
import threading
from pathlib import Path
from typing import Optional, Tuple

import httpx

from .config import ConfigManager
from .models import CastMember, MediaItem, MovieMetadata

TMDB_API = "https://api.themoviedb.org/3"
IMG = "https://image.tmdb.org/t/p"

# Mots-clés de release à retirer du nom de fichier.
_JUNK = re.compile(
    r"\b(2160p|1080p|720p|480p|4k|uhd|hdr10\+?|hdr|dolby\s*vision|dv|"
    r"bluray|blu-ray|brrip|bdrip|web-?dl|webrip|hdtv|remux|"
    r"x264|x265|h\.?264|h\.?265|hevc|avc|10bit|8bit|"
    r"truehd|atmos|dts(-hd)?(\.?ma)?|ac3|eac3|aac|ddp?5\.1|dd5\.1|flac|"
    r"multi|vff|vfq|vostfr|vo|french|truefrench|fr|en)\b",
    re.IGNORECASE,
)
_YEAR = re.compile(r"(?:^|[^0-9])((?:19|20)\d{2})(?:[^0-9]|$)")
_BRACKETS = re.compile(r"[\[\(\{][^\]\)\}]*[\]\)\}]")


def parse_filename(name: str) -> Tuple[str, Optional[int]]:
    """Extrait un titre propre + une année depuis un nom de fichier brut."""
    # 1) Année cherchée sur le nom normalisé AVANT tout retrait (gère "(2024)").
    raw = name.replace(".", " ").replace("_", " ").replace("-", " ")
    year: Optional[int] = None
    m = _YEAR.search(raw)
    if m:
        year = int(m.group(1))
        raw = raw[: m.start(1)]               # coupe tout après l'année

    # 2) Nettoyage : crochets/parenthèses puis mots-clés de release.
    s = _BRACKETS.sub(" ", raw)
    s = _JUNK.sub(" ", s)
    s = re.sub(r"[\[\]\(\)\{\}]", " ", s)     # retire crochets/parenthèses orphelins
    s = re.sub(r"\s{2,}", " ", s).strip(" -·")
    return (s or name, year)


class MetadataProvider:
    def __init__(self, cfgm: ConfigManager, base_dir: Path):
        self.cfgm = cfgm
        self.meta_dir = base_dir / ".pluxy_meta"
        self.meta_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()

    # -- Cache ------------------------------------------------------------- #
    def _cache_path(self, item_id: str) -> Path:
        return self.meta_dir / f"{item_id}.json"

    def cached(self, item_id: str) -> Optional[MovieMetadata]:
        p = self._cache_path(item_id)
        if p.exists():
            try:
                return MovieMetadata.model_validate_json(p.read_text(encoding="utf-8"))
            except Exception:
                return None
        return None

    def _save(self, item_id: str, meta: MovieMetadata) -> None:
        self._cache_path(item_id).write_text(
            meta.model_dump_json(indent=2), encoding="utf-8"
        )

    # -- API publique ------------------------------------------------------ #
    def get(self, item: MediaItem, force: bool = False) -> MovieMetadata:
        if not force:
            c = self.cached(item.id)
            if c is not None:
                return c

        title, year = parse_filename(item.title)
        cfg = self.cfgm.cfg.metadata
        meta: Optional[MovieMetadata] = None
        if cfg.enabled and cfg.tmdb_api_key:
            try:
                meta = self._fetch_tmdb(title, year, cfg.tmdb_api_key, cfg.language)
            except Exception:
                meta = None

        if meta is None:                       # repli : titre/année déduits
            meta = MovieMetadata(title=title, year=year, matched=False, source="filename")

        self._save(item.id, meta)
        return meta

    # -- TMDB -------------------------------------------------------------- #
    def _fetch_tmdb(self, title: str, year: Optional[int],
                    key: str, lang: str) -> Optional[MovieMetadata]:
        with httpx.Client(timeout=12.0) as cli:
            params = {"api_key": key, "query": title, "language": lang}
            if year:
                params["year"] = year
            r = cli.get(f"{TMDB_API}/search/movie", params=params)
            r.raise_for_status()
            results = r.json().get("results", [])
            if not results:
                return MovieMetadata(title=title, year=year, matched=False)

            best = results[0]
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
            poster_url=f"{IMG}/w500{j['poster_path']}" if j.get("poster_path") else None,
            backdrop_url=f"{IMG}/w1280{j['backdrop_path']}" if j.get("backdrop_path") else None,
            cast=cast,
            director=director,
            trailer_youtube_key=yt_key,
            trailer_url=f"https://www.youtube.com/watch?v={yt_key}" if yt_key else None,
            matched=True,
            source="tmdb",
        )
