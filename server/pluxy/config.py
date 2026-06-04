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
    # PRÉFÉRER LA LECTURE DIRECTE (façon Plex « Original/Maximum » en réseau local) :
    # quand le lecteur sait décoder le fichier, on l'envoie BRUT (qualité d'origine,
    # HDR préservé, zéro transcodage) -> le plafond de débit n'est PAS appliqué.
    # Le mettre à false pour bel et bien brider la bande passante Wi-Fi.
    prefer_direct_play: bool = True
    # Plafond de débit (Mbps) — appliqué seulement si prefer_direct_play=false.
    max_bitrate_mbps: int = 50
    vbv_bufsize_factor: float = 2.0
    gpu_index: int = 0
    # never = HDR TOUJOURS préservé (HDR->HDR) ; auto = tone map si client SDR ;
    # always = toujours convertir en SDR.
    # Défaut « never » : un panneau HDR (Philips) peut signaler à tort SDR au moment
    # de la requête -> on convertissait en SDR par erreur. On préserve donc le HDR par
    # défaut (le repli compat H.264 pour appareils non-HEVC tone-map de toute façon).
    hdr_tone_mapping: Literal["auto", "always", "never"] = "never"
    tone_map_algorithm: str = "hable"   # hable | mobius | reinhard | bt2390
    tone_map_peak_nits: int = 100


class AudioCfg(BaseModel):
    # Passthrough audio (pleine qualité) : copie le flux audio d'origine sans le
    # réencoder — DTS-HD Master Audio, TrueHD, Atmos, FLAC… sont transmis tels
    # quels. L'ampli HDMI/ARC/eARC les décode directement (aucune perte de qualité).
    # Désactiver seulement si l'audio est silencieux (appareil sans ampli compatible).
    force_audio_passthrough: bool = True
    # Codec/canaux de REPLI : utilisés uniquement quand force_audio_passthrough=False
    # ET que le client ne gère pas le codec source.
    target_codec: str = "eac3"         # eac3 (Dolby D+) | ac3 | aac
    target_channels: int = 6
    bitrate_kbps: int = 640
    # Champ hérité — conservé pour ne pas casser les configs existantes.
    downmix_lossless: bool = False


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


# Version du schéma de config (incrémentée à chaque migration de réglages).
SCHEMA_VERSION = 3


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
        self.path = data_dir / "config.json"
        self.default_path = default_path
        self.legacy_dir = legacy_dir
        self._lock = threading.RLock()
        self._cfg = self._load()

    def _load(self) -> PluxyConfig:
        # Priorité : config persistée -> ancienne config (migration) -> modèle par défaut.
        legacy = self.legacy_dir / "config.json" if self.legacy_dir else None
        if self.path.exists():
            src = self.path
        elif legacy and legacy.exists():
            src = legacy                              # migration depuis server/config.json
        else:
            src = self.default_path

        migrated = False
        if src.exists():
            data = json.loads(src.read_text(encoding="utf-8"))
            data, migrated = self._migrate(data)
            cfg = PluxyConfig.model_validate(data)
        else:
            cfg = PluxyConfig()

        Path(cfg.server.transcode_temp_dir).mkdir(parents=True, exist_ok=True)
        # Si on a chargé depuis le défaut/legacy OU appliqué une migration, on écrit
        # immédiatement dans l'emplacement stable pour figer la persistance.
        if src != self.path or migrated:
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
        if changed:
            data["schema_version"] = SCHEMA_VERSION
        return data, changed

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
