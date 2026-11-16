"""Disc discovery through libbluray/libdvdnav, without mounting or extracting an ISO."""
from __future__ import annotations

import json
import re
import subprocess
import threading
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

from .tools import NO_WINDOW


class DiscError(RuntimeError):
    pass


@dataclass(frozen=True)
class DiscTitle:
    key: str
    duration: float


@dataclass(frozen=True)
class Disc:
    kind: str
    titles: tuple[DiscTitle, ...]
    default_title: str
    data: dict | None = field(default=None, repr=False, compare=False)


_PLAYLIST = re.compile(r"playlist (\d{5})\.mpls \((\d+):(\d+):(\d+)\)")
_KEY = re.compile(r"^(bd\d{5}|dvd\d{1,2})$")
_slots = threading.BoundedSemaphore(2)
_lock = threading.RLock()


def input_arguments(path: str, title: str) -> list[str]:
    if not _KEY.fullmatch(title):
        raise DiscError("Titre de disque invalide")
    if title.startswith("bd"):
        return ["-playlist", str(int(title[2:])), "-i", "bluray:" + str(Path(path).resolve())]
    number = int(title[3:])
    if not 1 <= number <= 99:
        raise DiscError("Titre DVD invalide")
    return ["-f", "dvdvideo", "-title", str(number), "-i", str(Path(path).resolve())]


def _run(ffprobe: str, arguments: list[str]) -> subprocess.CompletedProcess:
    if not _slots.acquire(timeout=2):
        raise DiscError("Une analyse de disque est déjà en cours. Réessayez dans quelques instants.")
    try:
        return subprocess.run([ffprobe, "-hide_banner", "-v", "info", "-print_format", "json",
                               "-show_format", "-show_streams", "-show_chapters", *arguments],
                              capture_output=True, text=True, encoding="utf-8", errors="replace",
                              timeout=45, creationflags=NO_WINDOW)
    except subprocess.TimeoutExpired as exc:
        raise DiscError("Le disque ne répond pas ou est incomplet.") from exc
    except OSError as exc:
        raise DiscError("Le moteur de lecture des disques est indisponible. Réinstallez le serveur PC.") from exc
    finally:
        _slots.release()


def _data(result: subprocess.CompletedProcess) -> dict:
    try:
        data = json.loads(result.stdout)
    except (ValueError, TypeError):
        data = {}
    if result.returncode != 0 or not any(s.get("codec_type") == "video" for s in data.get("streams", [])):
        error = result.stderr.lower()
        if any(word in error for word in ("encrypted", "decrypt", "aacs", "bd+")):
            raise DiscError("Ce disque est protégé et ne peut pas être ouvert par le serveur.")
        if any(word in error for word in ("protocol not found", "unknown input format", "option not found")):
            raise DiscError("Mettez à jour le moteur FFmpeg du serveur PC : les modules de lecture ISO sont absents.")
        raise DiscError("Impossible de lire ce titre. Vérifiez que l’ISO vidéo est complet et lisible.")
    return data


def _stamp(path: str) -> tuple[str, int, int]:
    source = Path(path).resolve()
    if source.suffix.lower() != ".iso" or not source.is_file():
        raise DiscError("Image ISO introuvable")
    stat = source.stat()
    return str(source), stat.st_size, stat.st_mtime_ns


@lru_cache(maxsize=64)
def _inspect(ffprobe: str, path: str, size: int, modified: int) -> Disc:
    result = _run(ffprobe, ["-i", "bluray:" + path])
    if result.returncode == 0:
        data = _data(result)
        titles = tuple(DiscTitle("bd" + number, int(h) * 3600 + int(m) * 60 + int(s))
                       for number, h, m, s in dict.fromkeys(_PLAYLIST.findall(result.stderr)))
        selected = re.search(r"selected (\d{5})\.mpls", result.stderr)
        if titles and selected:
            return Disc("bluray", titles, "bd" + selected[1], data)
        raise DiscError("Le moteur n’a pas fourni la liste des titres Blu-ray.")
    if any(word in result.stderr.lower() for word in ("encrypted", "decrypt", "aacs", "bd+")):
        _data(result)
    # DVD-Video exposes title 1 without guessing that it is the main feature.
    dvd = _run(ffprobe, input_arguments(path, "dvd1"))
    if dvd.returncode != 0:
        if "protocol not found" in result.stderr.lower():
            _data(result)
        _data(dvd)
    data = _data(dvd)
    return Disc("dvd", (DiscTitle("dvd1", float(data.get("format", {}).get("duration") or 0)),), "dvd1", data)


def inspect_disc(ffprobe: str, path: str) -> Disc:
    # Collapse simultaneous discovery requests; successful results are invalidated by file changes.
    with _lock:
        return _inspect(ffprobe, *_stamp(path))


@lru_cache(maxsize=128)
def _probe_title(ffprobe: str, path: str, size: int, modified: int, title: str) -> dict:
    return _data(_run(ffprobe, input_arguments(path, title)))


def probe_title(ffprobe: str, path: str, title: str | None = None) -> tuple[str, dict]:
    disc = inspect_disc(ffprobe, path)
    chosen = title or disc.default_title
    selected = next((item for item in disc.titles if item.key == chosen), None)
    if selected is None:
        raise DiscError("Ce titre n’existe pas sur le disque")
    if chosen == disc.default_title and disc.data is not None:
        data = disc.data
    else:
        with _lock:
            data = _probe_title(ffprobe, *_stamp(path), chosen)
    # MPEG-TS timestamp wrap/discontinuities can fool ffprobe's duration estimate.
    # libbluray's playlist timeline is authoritative for navigation.
    if disc.kind == "bluray" and selected.duration > 0:
        data = {**data, "format": {**data.get("format", {}), "duration": str(selected.duration)}}
    from .disc_metadata import playlist_metadata
    languages, chapters = playlist_metadata(ffprobe, path, chosen)
    if languages or chapters:
        streams = []
        for stream in data.get("streams", []):
            try:
                language = languages.get(int(str(stream.get("id", "-1")), 0))
            except ValueError:
                language = None
            streams.append({**stream, "tags": {**stream.get("tags", {}), "language": language}} if language else stream)
        data = {**data, "streams": streams, "chapters": chapters or data.get("chapters", [])}
    return chosen, data


def remux_command(ffmpeg: str, path: str, title: str, start: float,
                  audio_codec: str | None = None, audio_channels: int = 2,
                  audio_languages: list[str | None] | None = None) -> list[str]:
    # Video is always copied; only incompatible audio is encoded.
    seek = ["-ss", f"{start:.3f}"] if start > 0 else []
    audio = []
    if audio_codec is not None:
        if audio_codec not in {"aac", "ac3", "eac3"}:
            raise DiscError("Format audio de conversion invalide")
        audio = ["-c:a", audio_codec, "-ac", str(max(1, min(audio_channels, 6))),
                 "-b:a", "192k" if audio_channels <= 2 else "640k"]
    metadata = []
    for index, language in enumerate(audio_languages or []):
        if language and re.fullmatch(r"[a-z]{3}", language):
            metadata += [f"-metadata:s:a:{index}", f"language={language}"]
    return [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", *seek,
            *input_arguments(path, title), "-map", "0:v", "-map", "0:a?", "-map", "0:s?",
            "-c", "copy", *audio, "-map_metadata", "0", *metadata, "-map_chapters", "0",
            # Some Blu-rays end with an untimed HEVC end-of-sequence packet.
            # Repair only absent timestamps; encoded payload and valid timings stay intact.
            "-bsf:v", r"setts=pts=if(eq(PTS\,NOPTS)\,PREV_OUTPTS+DURATION\,PTS):dts=if(eq(DTS\,NOPTS)\,PREV_OUTDTS+DURATION\,DTS)",
            "-avoid_negative_ts", "disabled", "-max_muxing_queue_size", "4096", "-f", "matroska", "pipe:1"]
