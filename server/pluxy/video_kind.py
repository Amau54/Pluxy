"""Classement local : le choix du dossier prime, puis les indices du fichier."""
import re
import unicodedata

SERIES = re.compile(r"(?:\bs\d{1,2}[ ._-]?e\d{1,3}|\b\d{1,2}x\d{2}\b|\b(?:saison|season|episode)[ ._-]*\d)", re.I)
CAMERA = re.compile(r"^(?:VID|MOV|PXL|IMG|GOPR|GH\d|GX\d|DJI|DSC)[ ._-]?\d|^\d{8}[ _-]\d{6}", re.I)
YEAR = re.compile(r"\b(?:19|20)\d{2}\b")
RELEASE = re.compile(r"\b(?:1080p|2160p|720p|bluray|blu ray|bdrip|webrip|web dl|dvdrip|x264|x265|h264|h265)\b")


def normalized(value: str) -> str:
    return re.sub(r"[._-]+", " ", "".join(c for c in unicodedata.normalize("NFKD", value)
                     if not unicodedata.combining(c)).casefold())


def classify_video(name: str, folders: list[str], category: str = "auto", duration: float = 0,
                   embedded_title: str = "") -> str:
    if category in {"movie", "series", "clip"}:
        return category
    parts = [normalized(part).strip() for part in folders]
    text = normalized(name)
    if CAMERA.search(name) or any(re.search(r"\b(?:dcim|camera|famille|personnel|vacances|souvenirs|gopro)\b", p) for p in parts):
        return "clip"
    if SERIES.search(text) or any(p in {"series", "serie", "tv", "shows", "tv shows", "tvshows"} or SERIES.search(p) for p in parts):
        return "series"
    if any(p in {"films", "film", "movies", "movie", "cinema"} for p in parts):
        return "movie"
    if YEAR.search(text) and RELEASE.search(text):
        return "movie"
    # Une date seule n'est pas un indice de cinéma : les souvenirs sont datés aussi.
    if duration >= 45 * 60 and (YEAR.search(text) or embedded_title.strip()):
        return "movie"
    return "clip"
