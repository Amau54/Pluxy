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
    # Métadonnées HDR10 statiques (réinjectées à l'encodage NVENC pour que la TV
    # bascule bien en mode HDR). Pré-formatées pour FFmpeg `-master_display`/`-max_cll`.
    master_display: Optional[str] = None     # ex. G(8500,39850)B(6550,2300)R(...)WP(...)L(max,min)
    max_cll: Optional[str] = None            # ex. "1000,400"
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
    tags: dict[str, str] = Field(default_factory=dict)

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
    kind: str = "movie"                # movie | series | photo | audio
    added_at: float = 0.0              # Première découverte par Pluxy
    modified_at: float = 0.0           # mtime technique : invalidation de l'analyse
    captured_at: Optional[float] = None  # Date EXIF, absente si inconnue
    # Regroupement des séries : nom (affichage) + CLÉ CANONIQUE de regroupement
    # (insensible casse/accents/tags/saison -> les épisodes d'une même série fusionnent
    # même répartis sur plusieurs dossiers/orthographes) + saison/épisode.
    series_name: Optional[str] = None
    series_key: Optional[str] = None
    season: Optional[int] = None
    episode: Optional[int] = None
    external_subs: List[str] = Field(default_factory=list)
    # Sous-titres externes décrits (langue + format) pour le client.
    subtitles: List[SubtitleTrack] = Field(default_factory=list)
    # Renseignés depuis le cache de métadonnées s'il existe (affichage grille).
    year: Optional[int] = None
    metadata_title: Optional[str] = None
    genres: List[str] = Field(default_factory=list)
    poster_url: Optional[str] = None
    thumbnail_url: Optional[str] = None
    folder: Optional[str] = None
    folder_id: Optional[str] = None
    relative_path: Optional[str] = None
    has_metadata: bool = False
    # État de visionnage, fusionné À LA VOLÉE depuis le suivi de lecture (persistant).
    watched: bool = False             # vu (lecture > 95 %)
    in_progress: bool = False         # commencé mais pas terminé
    progress: float = 0.0             # fraction de reprise 0..1
    last_played_at: float = 0.0       # epoch s, pour l'accueil « Reprendre »
    probe_complete: bool = True
    probe_retry_at: float = 0.0
    track_number: Optional[int] = None
    disc_number: Optional[int] = None
    artist: Optional[str] = None
    album: Optional[str] = None


class CastMember(BaseModel):
    name: str
    character: Optional[str] = None
    profile_url: Optional[str] = None


class Rating(BaseModel):
    """Note d'un site (TMDB, IMDb, Rotten Tomatoes, Metacritic…)."""
    source: str
    value: str                                 # ex. "8.2/10", "91%", "74/100"


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
    rating: Optional[float] = None             # /10 (note TMDB, compat)
    ratings: List[Rating] = Field(default_factory=list)   # notes multi-sites
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
    supported_video_codecs: List[str] = Field(
        default_factory=lambda: ["h264", "hevc"]
    )
    max_audio_channels: int = 6
    # Débit max que la liaison Wi-Fi du client tolère sans micro-coupures.
    max_bitrate_mbps: Optional[int] = None
    screen_width: int = 3840
    screen_height: int = 2160
    # Profil persistant côté téléviseur. Purement informatif pour les journaux et
    # diagnostics : aucune identité personnelle ni compte Pluxy.
    profile_id: Optional[str] = None
    profile_name: Optional[str] = None
    profile_revision: int = 0


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
    target_audio_channels: int = 6
    target_bitrate_mbps: Optional[int] = None
    container: str = "mkv"
    delivery: str = "direct"               # direct | hls
    # URL relative que le client doit ouvrir.
    stream_url: str = ""
    media: Optional[MediaInfo] = None
    # Diagnostic prélecture consommable sans démarrer le flux.
    playable: bool = True
    severity: str = "success"                # success | adaptation | warning | unsupported
    summary: str = ""
    profile_id: Optional[str] = None
    profile_name: Optional[str] = None
