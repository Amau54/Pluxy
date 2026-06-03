"""Schémas Pydantic : média analysé, capacités client, décision de lecture."""
from __future__ import annotations

from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field


class StreamKind(str, Enum):
    VIDEO = "video"
    AUDIO = "audio"
    SUBTITLE = "subtitle"


class MediaStream(BaseModel):
    index: int
    kind: StreamKind
    codec: str
    # Vidéo
    width: Optional[int] = None
    height: Optional[int] = None
    bit_rate: Optional[int] = None          # bits/s
    pix_fmt: Optional[str] = None
    color_transfer: Optional[str] = None    # smpte2084 (PQ/HDR10), arib-std-b67 (HLG)...
    color_primaries: Optional[str] = None
    is_hdr: bool = False
    # Audio
    channels: Optional[int] = None
    # Commun
    language: Optional[str] = None
    title: Optional[str] = None


class MediaInfo(BaseModel):
    path: str
    container: str                          # mkv, mp4...
    duration: float = 0.0                   # secondes
    size: int = 0                           # octets
    overall_bitrate: int = 0                # bits/s
    streams: List[MediaStream] = Field(default_factory=list)

    # Helpers ----------------------------------------------------------------
    @property
    def video(self) -> Optional[MediaStream]:
        return next((s for s in self.streams if s.kind == StreamKind.VIDEO), None)

    @property
    def audios(self) -> List[MediaStream]:
        return [s for s in self.streams if s.kind == StreamKind.AUDIO]

    @property
    def subtitles(self) -> List[MediaStream]:
        return [s for s in self.streams if s.kind == StreamKind.SUBTITLE]


class SubtitleTrack(BaseModel):
    """Sous-titre externe décrit (le client en déduit MIME + langue + label)."""
    index: int
    lang: Optional[str] = None              # ex. "fr", "en"
    format: str = "srt"                     # srt | vtt | ass | ssa
    label: str = ""


class MediaItem(BaseModel):
    """Entrée de bibliothèque (vue catalogue)."""
    id: str
    title: str
    path: str
    container: str
    size: int
    duration: float
    width: Optional[int] = None
    height: Optional[int] = None
    video_codec: Optional[str] = None
    is_hdr: bool = False
    external_subs: List[str] = Field(default_factory=list)
    # Sous-titres externes décrits (langue + format) pour le client.
    subtitles: List[SubtitleTrack] = Field(default_factory=list)
    # Renseignés depuis le cache de métadonnées s'il existe (affichage grille).
    year: Optional[int] = None
    poster_url: Optional[str] = None
    has_metadata: bool = False


class CastMember(BaseModel):
    name: str
    character: Optional[str] = None
    profile_url: Optional[str] = None


class MovieMetadata(BaseModel):
    """Métadonnées enrichies façon Plex (source TMDB)."""
    tmdb_id: Optional[int] = None
    title: str
    original_title: Optional[str] = None
    year: Optional[int] = None
    overview: Optional[str] = None
    tagline: Optional[str] = None
    genres: List[str] = Field(default_factory=list)
    runtime: Optional[int] = None              # minutes
    rating: Optional[float] = None             # /10
    poster_url: Optional[str] = None
    backdrop_url: Optional[str] = None
    cast: List[CastMember] = Field(default_factory=list)
    director: Optional[str] = None
    trailer_youtube_key: Optional[str] = None
    trailer_url: Optional[str] = None
    # État de la résolution (utile côté client/UI).
    matched: bool = False
    source: str = "tmdb"


class ClientCapabilities(BaseModel):
    """
    Capacités annoncées par le client Android TV (ExoPlayer/MediaCodec).
    Le Philips 803 supporte HEVC Main10 + HDR10 ; ARC classique => audio limité.
    """
    supports_hevc: bool = True
    supports_hevc_10bit: bool = True       # profil HEVC Main10 (10 bits)
    supports_h264: bool = True
    supports_av1: bool = False
    supports_hdr10: bool = True
    supports_hdr: bool = True              # tout type HDR (HDR10/HLG/DV)
    max_video_height: int = 2160           # hauteur max décodable (4K=2160)
    # Conteneurs lisibles directement par ExoPlayer.
    supported_containers: List[str] = Field(
        default_factory=lambda: ["mp4", "mkv", "webm", "ts"]
    )
    # Codecs audio passables vers l'ampli via ARC (passthrough) ou décodables.
    supported_audio_codecs: List[str] = Field(
        default_factory=lambda: ["aac", "ac3", "eac3", "mp3"]
    )
    max_audio_channels: int = 6
    # Débit max que la liaison Wi-Fi du client tolère sans micro-coupures.
    max_bitrate_mbps: Optional[int] = None
    screen_width: int = 3840
    screen_height: int = 2160


class PlaybackMode(str, Enum):
    DIRECT_PLAY = "direct_play"
    DIRECT_STREAM = "direct_stream"
    TRANSCODE = "transcode"


class PlaybackDecision(BaseModel):
    mode: PlaybackMode
    reasons: List[str] = Field(default_factory=list)
    # Transcodage en mode "compatibilité maximale" (H.264 1080p) car le lecteur
    # ne sait pas décoder la vidéo source (ex. HEVC Main10 non supporté).
    compat: bool = False
    # Détails effectifs du flux servi.
    video_action: str = "copy"             # copy | transcode
    audio_action: str = "copy"             # copy | transcode
    tone_map: bool = False
    target_video_codec: Optional[str] = None
    target_audio_codec: Optional[str] = None
    target_bitrate_mbps: Optional[int] = None
    container: str = "mkv"
    delivery: str = "direct"               # direct | hls
    # URL relative que le client doit ouvrir.
    stream_url: str = ""
    media: Optional[MediaInfo] = None
