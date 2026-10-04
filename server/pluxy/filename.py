"""
Parser robuste de noms de fichiers de films (titre + année + tags techniques).

Pipeline multi-passes inspiré de parse-torrent-title (PTN) / guessit / règles
Plex-Radarr, conçu d'après une recherche des meilleures pratiques :

  1. retrait du préfixe site (www.x.com - ) ;
  2. extraction du groupe de release AVANT toute conversion de séparateurs
     (-GROUP en suffixe, [TRACKER] en tête) — sinon il pollue le titre ;
  3. conversion des séparateurs `. _ +` et crochets/parenthèses en espaces
     (1 char -> 1 espace : les positions restent alignées) ; les tirets internes
     sont CONSERVÉS (ex. « Spider-Man ») ;
  4. détection de l'année : parenthésée prioritaire, sinon dernière année suivie
     d'un token technique fort (résolution/source/codec) ;
  5. le titre = texte avant l'année (ou avant le 1er token fort si pas d'année) ;
  6. pièges gérés : titres numériques (2012, 1917, 300, Blade Runner 2049),
     multi-années, année finale isolée traitée comme partie du titre.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import List, Optional

# --- Regex (issues de la recherche web : PTN / guessit / Radarr) ----------- #
_YEAR = re.compile(r"(?<!\d)((?:19[0-9]\d|20[0-2]\d))(?!\d)")
_YEAR_PAREN = re.compile(r"[\(\[]\s*((?:19[0-9]\d|20[0-2]\d))\s*[\)\]]")
_RESOLUTION = re.compile(r"(?i)\b(\d{3,4}[pi]|4k|uhd|2160p?|1440p|1080[pi]|720[pi]|480[pi]|fhd|qhd|8k)\b")
_SOURCE = re.compile(r"(?i)\b(web[ ._-]?dl|web[ ._-]?rip|webcap|web|blu[ ._-]?ray|bd[ ._-]?rip|bdremux|brrip|br[ ._-]?rip|hd[ ._-]?dvd|hddvd|dvd[ ._-]?rip|dvdr|hdtv|pdtv|hdrip|hd[ ._-]?rip|remux|cam|telesync|telecine|dvdscr|screener|scr|vodrip|satrip|workprint)\b")
_CODEC = re.compile(r"(?i)\b(x[ ._-]?264|x[ ._-]?265|h[ ._-]?264|h[ ._-]?265|hevc|avc|av1|xvid|divx|vc-?1|mpeg-?2)\b")
_BITDEPTH = re.compile(r"(?i)\b(8|10)[ ._-]?bits?\b")
_HDR = re.compile(r"(?i)\b(hdr10\+?|hdr|dolby[ ._-]?vision|dovi|hlg|sdr)\b")
_AUDIO = re.compile(r"(?i)\b(truehd|atmos|dts[ ._-]?hd[ ._-]?ma|dts[ ._-]?hd|dts[ ._-]?x|dts[ ._-]?es|dts|ddp?[ ._-]?5[ ._.]1|ddp|e[ ._-]?ac[ ._-]?3|ac[ ._-]?3|dolby[ ._-]?digital|aac|he[ ._-]?aac|flac|opus|lpcm|[257][ ._.]1|2[ ._.]0)\b")
_LANG = re.compile(r"(?i)\b(multi|truefrench|vff|vf2|vfi|vfq|vfb|vfo|vof|vostfr|vost|vf|vo|french|english|eng|italian|ita|spanish|spa|german|ger|russian|rus|japanese|jap|korean|kor|hindi|nordic)\b")
_EDITION = re.compile(r"(?i)\b(director'?s[ ._-]?cut|extended(?:[ ._-]?(?:cut|edition))?|theatrical(?:[ ._-]?cut)?|unrated|uncut|remastered|imax|final[ ._-]?cut|ultimate[ ._-]?edition|special[ ._-]?edition|international[ ._-]?cut|anniversary[ ._-]?edition|despecialized)\b")
_PROPER = re.compile(r"(?i)\b(proper|repack|rerip|internal|limited|readnfo|read[ ._-]?nfo|hardcoded|widescreen|3d|half[ ._-]?sbs|sbs)\b")
_GROUP_DASH = re.compile(r"(?i)-([a-z0-9]{2,}(?:\[[a-z0-9.]+\])?)\s*$")
_LEAD_BRACKET = re.compile(r"^\s*\[[^\]]+\]\s*")
_WEBSITE = re.compile(r"(?i)^\s*(?:www\.)?[\w-]+\.(?:com|net|org|mx|to|tv|info|eu)\s*-?\s*")

_STRONG = (_RESOLUTION, _SOURCE, _CODEC)
_ALL_TAGS = {
    "resolution": _RESOLUTION, "source": _SOURCE, "codec": _CODEC,
    "bitdepth": _BITDEPTH, "hdr": _HDR, "audio": _AUDIO,
    "language": _LANG, "edition": _EDITION, "proper": _PROPER,
}


@dataclass
class ParsedFilename:
    title: str
    year: Optional[int] = None
    resolution: Optional[str] = None
    source: Optional[str] = None
    codec: Optional[str] = None
    hdr: Optional[str] = None
    edition: Optional[str] = None
    languages: List[str] = field(default_factory=list)
    group: Optional[str] = None


def _strip_ext(name: str) -> str:
    m = re.search(r"\.([A-Za-z0-9]{2,4})$", name)
    if m and m.group(1).lower() in {
        "mkv", "mp4", "m4v", "avi", "mov", "ts", "webm", "iso", "wmv", "flv", "mpg"
    }:
        return name[: m.start()]
    return name


def _to_spaces(s: str) -> str:
    """Convertit `. _ +` et crochets/parenthèses en espaces (1:1, indices alignés)."""
    out = list(s)
    for i, ch in enumerate(s):
        if ch in "._+[](){}":
            out[i] = " "
    return "".join(out)


def parse(name: str) -> ParsedFilename:
    raw = _strip_ext(name)
    s = _WEBSITE.sub("", raw)

    # Groupe de release : suffixe -GROUP puis crochet de tête [TRACKER].
    group: Optional[str] = None
    m = _GROUP_DASH.search(s)
    if m:
        group = m.group(1)
        s = s[: m.start()]
    mb = _LEAD_BRACKET.match(s)
    if mb:
        if group is None:
            group = mb.group(0).strip(" []")
        s = s[mb.end():]

    # Année parenthésée (haute confiance) repérée AVANT conversion.
    paren = _YEAR_PAREN.search(s)

    work = _to_spaces(s)

    year: Optional[int] = None
    cut: Optional[int] = None
    if paren:
        year = int(paren.group(1))
        cut = paren.start()
    else:
        strong_idx = _first_match_start(work, _STRONG)
        candidates = list(_YEAR.finditer(work))
        chosen = None
        if strong_idx is not None:
            before = [c for c in candidates if c.start() < strong_idx]
            if before:
                chosen = before[-1]
        if chosen is None:
            # Dernière année AYANT du contenu après elle (sinon = partie du titre).
            for c in reversed(candidates):
                if work[c.end():].strip(" -._[](){}"):
                    chosen = c
                    break
        if chosen is not None:
            year = int(chosen.group(1))
            cut = chosen.start()
        else:
            strong_idx = _first_match_start(work, _STRONG)
            cut = strong_idx

    title = _clean_title(work[:cut] if cut is not None else work)
    if not title:
        if year is not None:          # le titre EST l'année (ex. « 2012 », « 1917 »)
            title = str(year)
            year = None
        else:
            title = _clean_title(work) or name

    return ParsedFilename(
        title=title,
        year=year,
        resolution=_first_value(work, _RESOLUTION),
        source=_first_value(work, _SOURCE),
        codec=_first_value(work, _CODEC),
        hdr=_first_value(work, _HDR),
        edition=_first_value(work, _EDITION),
        languages=[mm.group(0) for mm in _LANG.finditer(work)],
        group=group,
    )


def _first_match_start(work: str, patterns) -> Optional[int]:
    starts = [m.start() for p in patterns for m in [p.search(work)] if m]
    return min(starts) if starts else None


def _first_value(work: str, pattern) -> Optional[str]:
    m = pattern.search(work)
    return m.group(0) if m else None


def _clean_title(t: str) -> str:
    t = re.sub(r"[\[\]\(\)\{\}]", " ", t)
    t = re.sub(r"\s{2,}", " ", t)
    return t.strip(" -._·")
