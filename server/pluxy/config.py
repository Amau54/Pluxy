"""
Modèle de données de configuration + persistance JSON (Workflow 4).

Chaque section du JSON est typée par un modèle Pydantic. Le fichier `config.json`
(ou `config.default.json` au premier lancement) est la source de vérité ; l'UI Web
lit/écrit ces mêmes champs via l'API `/api/settings`.
"""
from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import List, Literal

from pydantic import BaseModel, Field


# --------------------------------------------------------------------------- #
#  Sections                                                                   #
# --------------------------------------------------------------------------- #
class ServerCfg(BaseModel):
    name: str = "Pluxy"
    host: str = "0.0.0.0"
    port: int = 8420
    media_dirs: List[str] = Field(default_factory=list)
    transcode_temp_dir: str = "./.pluxy_cache"
    scan_extensions: List[str] = Field(
        default_factory=lambda: [".mkv", ".mp4", ".m4v", ".mov", ".avi", ".ts", ".webm"]
    )


class FfmpegCfg(BaseModel):
    ffmpeg_path: str = "ffmpeg"
    ffprobe_path: str = "ffprobe"
    log_level: str = "error"


class TranscodingCfg(BaseModel):
    # Toggle UI : activer/désactiver l'accélération matérielle NVENC.
    hardware_acceleration: bool = True
    encoder: str = "hevc_nvenc"
    decoder_hwaccel: str = "cuda"
    preset: str = "p5"          # p1 (rapide) .. p7 (qualité)
    tune: str = "hq"
    rc_mode: str = "vbr"
    # Force le transcodage même si Direct Play serait possible.
    force_transcode: bool = False
    # Sélecteur de débit max (Bitrate Cap) — bride la bande passante Wi-Fi.
    max_bitrate_mbps: int = 50
    vbv_bufsize_factor: float = 2.0
    gpu_index: int = 0
    # auto = tone map seulement si client SDR ; always = toujours ; never = jamais.
    hdr_tone_mapping: Literal["auto", "always", "never"] = "auto"
    tone_map_algorithm: str = "hable"   # hable | mobius | reinhard | bt2390
    tone_map_peak_nits: int = 100


class AudioCfg(BaseModel):
    # Downmix automatique des pistes lossless vers un codec compatible ARC.
    downmix_lossless: bool = True
    lossless_codecs: List[str] = Field(
        default_factory=lambda: ["truehd", "dts", "dtshd", "flac",
                                 "pcm_s24le", "pcm_s16le", "mlp"]
    )
    target_codec: str = "ac3"          # ac3 (universel ARC) | eac3
    target_channels: int = 6
    bitrate_kbps: int = 640


class NetworkCfg(BaseModel):
    delivery: Literal["hls", "direct"] = "hls"
    hls_segment_duration: int = 3
    hls_playlist_size: int = 0
    hls_flags: str = "independent_segments"
    direct_play_enabled: bool = True
    direct_stream_enabled: bool = True


class ClientBufferCfg(BaseModel):
    """Paramètres transmis au client pour le pré-buffer ExoPlayer."""
    min_buffer_ms: int = 50_000
    max_buffer_ms: int = 120_000
    buffer_for_playback_ms: int = 5_000
    buffer_for_playback_after_rebuffer_ms: int = 10_000
    target_buffer_bytes_mb: int = 256
    back_buffer_ms: int = 30_000


class SubtitlesCfg(BaseModel):
    external_extensions: List[str] = Field(
        default_factory=lambda: [".srt", ".ass", ".ssa", ".vtt"]
    )
    prefer_external: bool = True
    burn_in: bool = False


class MetadataCfg(BaseModel):
    """Enrichissement façon Plex via TMDB (synopsis, casting, affiches, trailer)."""
    enabled: bool = True
    provider: str = "tmdb"
    # Clé API TMDB (gratuite sur themoviedb.org). Vide => métadonnées désactivées.
    tmdb_api_key: str = ""
    language: str = "fr-FR"
    # Enrichit automatiquement au scan (sinon à la demande).
    auto_fetch_on_scan: bool = True


class DiscoveryCfg(BaseModel):
    """Découverte auto du serveur par le client (broadcast UDP)."""
    enabled: bool = True
    udp_port: int = 8421


# --------------------------------------------------------------------------- #
#  Racine                                                                      #
# --------------------------------------------------------------------------- #
class PluxyConfig(BaseModel):
    server: ServerCfg = Field(default_factory=ServerCfg)
    ffmpeg: FfmpegCfg = Field(default_factory=FfmpegCfg)
    transcoding: TranscodingCfg = Field(default_factory=TranscodingCfg)
    audio: AudioCfg = Field(default_factory=AudioCfg)
    network: NetworkCfg = Field(default_factory=NetworkCfg)
    client_buffer: ClientBufferCfg = Field(default_factory=ClientBufferCfg)
    subtitles: SubtitlesCfg = Field(default_factory=SubtitlesCfg)
    metadata: MetadataCfg = Field(default_factory=MetadataCfg)
    discovery: DiscoveryCfg = Field(default_factory=DiscoveryCfg)


# --------------------------------------------------------------------------- #
#  Gestionnaire thread-safe de persistance                                    #
# --------------------------------------------------------------------------- #
class ConfigManager:
    """Charge/sauvegarde la configuration et expose un objet `PluxyConfig` vivant."""

    def __init__(self, base_dir: Path):
        self.base_dir = base_dir
        self.path = base_dir / "config.json"
        self.default_path = base_dir / "config.default.json"
        self._lock = threading.RLock()
        self._cfg = self._load()

    def _load(self) -> PluxyConfig:
        src = self.path if self.path.exists() else self.default_path
        if src.exists():
            data = json.loads(src.read_text(encoding="utf-8"))
            cfg = PluxyConfig.model_validate(data)
        else:
            cfg = PluxyConfig()
        # Garantit l'existence du cache de transcodage.
        Path(cfg.server.transcode_temp_dir).mkdir(parents=True, exist_ok=True)
        return cfg

    @property
    def cfg(self) -> PluxyConfig:
        with self._lock:
            return self._cfg

    def update(self, partial: dict) -> PluxyConfig:
        """Fusionne un patch partiel (depuis l'UI) et persiste."""
        with self._lock:
            merged = self._deep_merge(self._cfg.model_dump(), partial)
            self._cfg = PluxyConfig.model_validate(merged)
            self.save()
            return self._cfg

    def save(self) -> None:
        with self._lock:
            self.path.write_text(
                json.dumps(self._cfg.model_dump(), indent=2, ensure_ascii=False),
                encoding="utf-8",
            )

    @staticmethod
    def _deep_merge(base: dict, patch: dict) -> dict:
        for k, v in patch.items():
            if isinstance(v, dict) and isinstance(base.get(k), dict):
                base[k] = ConfigManager._deep_merge(base[k], v)
            else:
                base[k] = v
        return base
