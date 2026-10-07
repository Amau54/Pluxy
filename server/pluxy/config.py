"""
Modèle de données de configuration + persistance JSON (Workflow 4).

Chaque section du JSON est typée par un modèle Pydantic. Le fichier `config.json`
(ou `config.default.json` au premier lancement) est la source de vérité ; l'UI Web
lit/écrit ces mêmes champs via l'API `/api/settings`.
"""
from __future__ import annotations

import json
import errno
import os
import shutil
import threading
import time
from pathlib import Path
from typing import List, Literal

from pydantic import BaseModel, Field, field_validator, model_validator

_CONFIG_READ_ATTEMPTS = 6
VIDEO_SCAN_EXTENSIONS = [".mkv", ".mp4", ".m4v", ".mov", ".avi", ".ts", ".webm"]
DEFAULT_SCAN_EXTENSIONS = VIDEO_SCAN_EXTENSIONS + [
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".tif", ".tiff",
    ".mp3", ".flac", ".m4a", ".m4b", ".aac", ".ogg", ".oga", ".opus", ".wav", ".wma",
]


def _read_config_text(path: Path) -> str:
    """Tolère les verrous Windows brefs sans confondre accès refusé et JSON cassé."""
    for attempt in range(_CONFIG_READ_ATTEMPTS):
        try:
            return path.read_text(encoding="utf-8")
        except PermissionError as error:
            winerror = getattr(error, "winerror", None)
            transient = os.name == "nt" and (
                winerror in {5, 32, 33}
                or (winerror is None and error.errno == errno.EACCES)
            )
            if not transient or attempt == _CONFIG_READ_ATTEMPTS - 1:
                raise
            time.sleep(0.01 * (2 ** attempt))
    raise AssertionError("Lecture de configuration sans résultat")


# --------------------------------------------------------------------------- #
#  Sections                                                                   #
# --------------------------------------------------------------------------- #
class ServerCfg(BaseModel):
    name: str = Field(default="Pluxy", min_length=1, max_length=60)
    host: str = Field(default="0.0.0.0", min_length=1, max_length=253)
    port: int = Field(default=8420, ge=1, le=65535)
    media_dirs: List[str] = Field(default_factory=list, max_length=128)
    media_dir_types: dict[str, Literal["auto", "movie", "series", "clip"]] = Field(default_factory=dict, max_length=128)
    transcode_temp_dir: str = Field(default="./.pluxy_cache", min_length=1, max_length=4096)
    transcode_cache_quota_mb: int = Field(default=8192, ge=16, le=1_048_576)
    transcode_min_free_mb: int = Field(default=1024, ge=0, le=1_048_576)
    # Démarrer le serveur automatiquement à l'ouverture de session Windows.
    autostart: bool = False
    scan_extensions: List[str] = Field(
        default_factory=lambda: list(DEFAULT_SCAN_EXTENSIONS),
        max_length=64,
    )

    @field_validator("media_dirs")
    @classmethod
    def valid_media_dirs(cls, values: List[str]) -> List[str]:
        for value in values:
            if not isinstance(value, str) or not value.strip() or len(value) > 4096 or "\x00" in value:
                raise ValueError("Dossier media invalide")
        return values

    @field_validator("scan_extensions")
    @classmethod
    def valid_scan_extensions(cls, values: List[str]) -> List[str]:
        clean: list[str] = []
        for value in values:
            normalized = value.strip().lower()
            if not normalized.startswith(".") or len(normalized) > 16 or not normalized[1:].isalnum():
                raise ValueError("Extension de scan invalide")
            if normalized not in clean:
                clean.append(normalized)
        return clean


class FfmpegCfg(BaseModel):
    ffmpeg_path: str = Field(default="ffmpeg", min_length=1, max_length=4096)
    ffprobe_path: str = Field(default="ffprobe", min_length=1, max_length=4096)
    log_level: str = Field(default="error", min_length=1, max_length=32)

    @field_validator("ffmpeg_path", "ffprobe_path")
    @classmethod
    def installed_tool(cls, value: str) -> str:
        if os.name == "nt" and value in {"ffmpeg", "ffprobe"}:
            cached = Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "Pluxy" / "FFmpeg" / "bin" / f"{value}.exe"
            if cached.is_file():
                return str(cached)
        return value


class TranscodingCfg(BaseModel):
    # Toggle UI : activer/désactiver l'accélération matérielle NVENC.
    hardware_acceleration: bool = True
    encoder: str = "hevc_nvenc"
    decoder_hwaccel: str = "cuda"
    preset: str = Field(default="p5", min_length=1, max_length=32)  # p1 .. p7
    tune: str = Field(default="hq", min_length=1, max_length=32)
    rc_mode: str = Field(default="vbr", min_length=1, max_length=32)
    # Force le transcodage même si Direct Play serait possible.
    force_transcode: bool = False
    # PRÉFÉRER LA LECTURE DIRECTE (façon Plex « Original/Maximum » en réseau local) :
    # quand le lecteur sait décoder le fichier, on l'envoie BRUT (qualité d'origine,
    # HDR préservé, zéro transcodage) -> le plafond de débit n'est PAS appliqué.
    # Le mettre à false pour bel et bien brider la bande passante Wi-Fi.
    prefer_direct_play: bool = True
    # Plafond de débit (Mbps) — appliqué seulement si prefer_direct_play=false.
    max_bitrate_mbps: int = Field(default=50, ge=1, le=300)
    vbv_bufsize_factor: float = Field(default=2.0, ge=1.0, le=8.0)
    gpu_index: int = Field(default=0, ge=0, le=16)
    # never = HDR TOUJOURS préservé (HDR->HDR) ; auto = tone map si client SDR ;
    # always = toujours convertir en SDR.
    # Défaut « never » : un panneau HDR (Philips) peut signaler à tort SDR au moment
    # de la requête -> on convertissait en SDR par erreur. On préserve donc le HDR par
    # défaut (le repli compat H.264 pour appareils non-HEVC tone-map de toute façon).
    hdr_tone_mapping: Literal["auto", "always", "never"] = "never"
    tone_map_algorithm: Literal["hable", "mobius", "reinhard", "bt2390"] = "hable"
    tone_map_peak_nits: int = Field(default=100, ge=50, le=10_000)


class AudioCfg(BaseModel):
    # Passthrough audio (pleine qualité) : copie le flux audio d'origine sans le
    # réencoder — DTS-HD Master Audio, TrueHD, Atmos, FLAC… sont transmis tels
    # quels. L'ampli HDMI/ARC/eARC les décode directement (aucune perte de qualité).
    # Désactiver seulement si l'audio est silencieux (appareil sans ampli compatible).
    force_audio_passthrough: bool = True
    # Codec/canaux de REPLI : utilisés uniquement quand force_audio_passthrough=False
    # ET que le client ne gère pas le codec source.
    target_codec: Literal["eac3", "ac3", "aac"] = "eac3"
    target_channels: int = Field(default=6, ge=1, le=8)
    bitrate_kbps: int = Field(default=640, ge=64, le=1536)
    # Champ hérité — conservé pour ne pas casser les configs existantes.
    downmix_lossless: bool = False


class NetworkCfg(BaseModel):
    delivery: Literal["hls", "direct"] = "hls"
    hls_segment_duration: int = Field(default=3, ge=2, le=10)
    hls_playlist_size: int = Field(default=0, ge=0, le=10_000)
    hls_flags: str = Field(default="independent_segments", min_length=1, max_length=128)
    direct_play_enabled: bool = True
    direct_stream_enabled: bool = True


class ClientBufferCfg(BaseModel):
    """Paramètres transmis au client pour le pré-buffer ExoPlayer."""
    min_buffer_ms: int = Field(default=60_000, ge=1_000, le=600_000)
    max_buffer_ms: int = Field(default=120_000, ge=5_000, le=600_000)
    buffer_for_playback_ms: int = Field(default=5_000, ge=250, le=120_000)
    buffer_for_playback_after_rebuffer_ms: int = Field(default=15_000, ge=250, le=120_000)
    # Gros tampon octets : c'est le facteur limitant pour les 4K à haut débit crête
    # (un plafond trop bas fige la lecture malgré une durée de buffer élevée).
    target_buffer_bytes_mb: int = Field(default=600, ge=16, le=1024)
    back_buffer_ms: int = Field(default=30_000, ge=0, le=300_000)

    @model_validator(mode="after")
    def valid_order(self):
        if self.min_buffer_ms > self.max_buffer_ms:
            raise ValueError("min_buffer_ms doit etre inferieur ou egal a max_buffer_ms")
        if self.buffer_for_playback_ms > self.min_buffer_ms:
            raise ValueError("buffer_for_playback_ms ne peut pas depasser min_buffer_ms")
        if self.buffer_for_playback_after_rebuffer_ms > self.min_buffer_ms:
            raise ValueError(
                "buffer_for_playback_after_rebuffer_ms ne peut pas depasser min_buffer_ms"
            )
        return self


class SubtitlesCfg(BaseModel):
    external_extensions: List[str] = Field(
        default_factory=lambda: [".srt", ".ass", ".ssa", ".vtt"],
        max_length=32,
    )
    prefer_external: bool = True
    burn_in: bool = False


class MetadataCfg(BaseModel):
    """Enrichissement façon Plex via TMDB (synopsis, casting, affiches, trailer)."""
    enabled: bool = True
    provider: str = Field(default="tmdb", min_length=1, max_length=32)
    # Clé API TMDB (gratuite sur themoviedb.org). Vide => métadonnées désactivées.
    tmdb_api_key: str = Field(default="", max_length=4096)
    # Clé API OMDb (gratuite sur omdbapi.com) : notes IMDb / Rotten Tomatoes / Metacritic.
    # Vide => seule la note TMDB est affichée.
    omdb_api_key: str = Field(default="", max_length=4096)
    language: str = Field(default="fr-FR", pattern=r"^[a-z]{2}-[A-Z]{2}$")
    # Enrichit automatiquement au scan (sinon à la demande).
    auto_fetch_on_scan: bool = True


class DiscoveryCfg(BaseModel):
    """Découverte auto du serveur par le client (broadcast UDP)."""
    enabled: bool = True
    udp_port: int = Field(default=8421, ge=1, le=65535)


# Version du schéma de config (incrémentée à chaque migration de réglages).
SCHEMA_VERSION = 4


# --------------------------------------------------------------------------- #
#  Racine                                                                      #
# --------------------------------------------------------------------------- #
class PluxyConfig(BaseModel):
    schema_version: int = SCHEMA_VERSION
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

    def __init__(self, data_dir: Path, default_path: Path, legacy_dir: Path | None = None):
        # config.json est écrit dans un emplacement STABLE (survit aux mises à jour).
        self.data_dir = data_dir
        self.path = data_dir / "config.json"
        self.default_path = default_path
        self.legacy_dir = legacy_dir
        self._lock = threading.RLock()
        self._cfg = self._load()

    def _load(self) -> PluxyConfig:
        # Priorité : config persistée -> ancienne config (migration) -> modèle par défaut.
        # Une copie invalide est conservée pour diagnostic au lieu d'empêcher le serveur
        # de démarrer ou d'être silencieusement détruite.
        legacy = self.legacy_dir / "config.json" if self.legacy_dir else None
        candidates = [self.path]
        if legacy is not None and legacy != self.path:
            candidates.append(legacy)
        if self.default_path not in candidates:
            candidates.append(self.default_path)

        cfg: PluxyConfig | None = None
        src: Path | None = None
        migrated = False
        invalid_persisted = False
        for candidate in candidates:
            try:
                # read_text distingue un fichier absent d'un fichier inaccessible;
                # exists() peut masquer un refus d'accès sur certaines versions Python.
                data = json.loads(_read_config_text(candidate))
                data, candidate_migrated = self._migrate(data)
                cfg = PluxyConfig.model_validate(data)
                src = candidate
                migrated = candidate_migrated
                break
            except FileNotFoundError:
                continue
            except (ValueError, TypeError):
                # Seuls le JSON/UTF-8 et le modèle invalides justifient une recovery.
                # Les erreurs d'E/S remontent et laissent le fichier utilisateur intact.
                if candidate == self.path:
                    invalid_persisted = True
                    backup = self.path.with_name(
                        f"{self.path.stem}.invalid-{time.time_ns()}{self.path.suffix}"
                    )
                    try:
                        shutil.copy2(self.path, backup)
                    except Exception:
                        pass

        if cfg is None:
            cfg = PluxyConfig()

        try:
            Path(cfg.server.transcode_temp_dir).mkdir(parents=True, exist_ok=True)
        except Exception:
            fallback_cache = self.data_dir / ".pluxy_cache"
            fallback_cache.mkdir(parents=True, exist_ok=True)
            cfg.server.transcode_temp_dir = str(fallback_cache)
            migrated = True
        # Si on a chargé depuis le défaut/legacy OU appliqué une migration, on écrit
        # immédiatement dans l'emplacement stable pour figer la persistance.
        if src != self.path or migrated or invalid_persisted:
            try:
                from .paths import atomic_write_text
                atomic_write_text(
                    self.path, json.dumps(cfg.model_dump(), indent=2, ensure_ascii=False)
                )
            except Exception:
                pass
        return cfg

    @staticmethod
    def _migrate(data: dict) -> tuple[dict, bool]:
        """
        Migre une config persistée vers le schéma courant. Renvoie (data, modifié).
        Conserve les choix EXPLICITES de l'utilisateur ; ne corrige que les valeurs
        héritées d'anciens défauts problématiques.
        """
        if not isinstance(data, dict):
            return data, False
        ver = data.get("schema_version", 1)
        changed = False
        # v1 -> v2 : hdr_tone_mapping "auto" -> "never" (préserver le HDR).
        if ver < 2:
            tc = data.get("transcoding")
            if isinstance(tc, dict) and tc.get("hdr_tone_mapping") == "auto":
                tc["hdr_tone_mapping"] = "never"
            changed = True
        # v2 -> v3 : activer force_audio_passthrough (pleine qualité audio).
        # L'ancien champ downmix_lossless=True provoquait un downmix silencieux.
        if ver < 3:
            au = data.setdefault("audio", {})
            if isinstance(au, dict):
                au["force_audio_passthrough"] = True
                au["downmix_lossless"] = False
                # Codec de repli mis à jour (eac3 > ac3).
                if au.get("target_codec") == "ac3":
                    au["target_codec"] = "eac3"
            changed = True
        if ver < 4:
            server = data.get("server")
            if isinstance(server, dict) and server.get("scan_extensions") == VIDEO_SCAN_EXTENSIONS:
                server["scan_extensions"] = list(DEFAULT_SCAN_EXTENSIONS)
            changed = True
        if changed:
            data["schema_version"] = SCHEMA_VERSION
        return data, changed

    @property
    def cfg(self) -> PluxyConfig:
        with self._lock:
            return self._cfg

    def update(self, partial: dict) -> PluxyConfig:
        """Fusionne un patch partiel (depuis l'UI) et persiste."""
        if not isinstance(partial, dict) or not partial:
            raise ValueError("Patch de configuration invalide")
        with self._lock:
            merged = self._deep_merge(self._cfg.model_dump(), partial)
            candidate = PluxyConfig.model_validate(merged)
            from .paths import atomic_write_text
            atomic_write_text(
                self.path, json.dumps(candidate.model_dump(), indent=2, ensure_ascii=False)
            )
            # Ne publie le changement qu'une fois la sauvegarde réussie : une
            # erreur disque conserve la même configuration en mémoire et sur disque.
            self._cfg = candidate
            return self._cfg

    def save(self) -> None:
        from .paths import atomic_write_text
        with self._lock:
            atomic_write_text(
                self.path,
                json.dumps(self._cfg.model_dump(), indent=2, ensure_ascii=False),
            )

    @staticmethod
    def _deep_merge(base: dict, patch: dict) -> dict:
        for k, v in patch.items():
            if isinstance(v, dict) and isinstance(base.get(k), dict):
                base[k] = ConfigManager._deep_merge(base[k], v)
            else:
                base[k] = v
        return base
