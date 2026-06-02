"""
Moteur de décision Direct Play / Direct Stream / Transcode (Workflow 1).

Compare les caractéristiques du média (MediaInfo) aux capacités du client
(ClientCapabilities) et aux réglages serveur (PluxyConfig) pour choisir le mode
le moins coûteux qui garantit une lecture stable.
"""
from __future__ import annotations

from .config import PluxyConfig
from .models import (
    ClientCapabilities,
    MediaInfo,
    PlaybackDecision,
    PlaybackMode,
)

# Conteneurs que ExoPlayer remux facilement en Direct Stream.
_VIDEO_CODEC_OK = {"hevc", "h265", "h264", "avc"}


def decide(
    media: MediaInfo,
    client: ClientCapabilities,
    cfg: PluxyConfig,
) -> PlaybackDecision:
    reasons: list[str] = []
    v = media.video
    tc = cfg.transcoding

    # Plafond de débit effectif : min(réglage serveur, tolérance client Wi-Fi).
    cap_mbps = tc.max_bitrate_mbps
    if client.max_bitrate_mbps:
        cap_mbps = min(cap_mbps, client.max_bitrate_mbps)

    # ---- 1. Faut-il transcoder la VIDÉO ? --------------------------------- #
    video_needs_transcode = False

    if tc.force_transcode:
        video_needs_transcode = True
        reasons.append("Transcodage forcé par l'utilisateur.")

    if v is None:
        reasons.append("Aucune piste vidéo détectée.")
    else:
        codec_ok = v.codec in _VIDEO_CODEC_OK and (
            client.supports_hevc if v.codec in ("hevc", "h265") else client.supports_h264
        )
        if not codec_ok:
            video_needs_transcode = True
            reasons.append(f"Codec vidéo {v.codec} non supporté par le client.")

        # Débit trop élevé pour la liaison Wi-Fi -> transcode avec bitrate cap.
        src_mbps = (media.overall_bitrate or 0) / 1_000_000
        if cap_mbps and src_mbps > cap_mbps:
            video_needs_transcode = True
            reasons.append(
                f"Débit source {src_mbps:.0f} Mbps > plafond {cap_mbps} Mbps (Wi-Fi)."
            )

    # ---- 2. Tone mapping HDR -> SDR ? ------------------------------------ #
    tone_map = False
    src_is_hdr = bool(v and v.is_hdr)
    if tc.hdr_tone_mapping == "always" and src_is_hdr:
        tone_map = True
        reasons.append("Tone mapping HDR->SDR forcé (réglage 'always').")
    elif tc.hdr_tone_mapping == "auto" and src_is_hdr and not client.supports_hdr:
        tone_map = True
        reasons.append("Client SDR : tone mapping HDR->SDR requis.")
    if tone_map:
        video_needs_transcode = True

    # ---- 3. Faut-il toucher à l'AUDIO ? ---------------------------------- #
    audio_needs_transcode = False
    a = media.audios[0] if media.audios else None
    if a is not None:
        lossless = a.codec in {c.lower() for c in cfg.audio.lossless_codecs}
        unsupported = a.codec not in {c.lower() for c in client.supported_audio_codecs}
        too_many_ch = (a.channels or 0) > client.max_audio_channels
        if (cfg.audio.downmix_lossless and lossless) or unsupported or too_many_ch:
            audio_needs_transcode = True
            reasons.append(
                f"Audio {a.codec} {a.channels}ch -> "
                f"{cfg.audio.target_codec} {cfg.audio.target_channels}ch (ARC)."
            )

    # ---- 4. Faut-il ré-encapsuler (conteneur) ? -------------------------- #
    container_ok = media.container in client.supported_containers
    if not container_ok:
        reasons.append(f"Conteneur {media.container} non supporté -> remux.")

    # ---- 5. Synthèse du mode -------------------------------------------- #
    hw = tc.hardware_acceleration

    if video_needs_transcode and hw:
        mode = PlaybackMode.TRANSCODE
        target_codec = tc.encoder
        delivery = cfg.network.delivery
        container = "ts" if delivery == "hls" else "mkv"
    elif video_needs_transcode and not hw:
        # NVENC désactivé : on transcode quand même mais on le signale.
        mode = PlaybackMode.TRANSCODE
        target_codec = tc.encoder
        delivery = cfg.network.delivery
        container = "ts" if delivery == "hls" else "mkv"
        reasons.append("⚠ NVENC désactivé : transcodage matériel indisponible.")
    elif audio_needs_transcode or not container_ok:
        mode = PlaybackMode.DIRECT_STREAM
        target_codec = None
        delivery = "direct"
        container = "mkv"
    else:
        mode = PlaybackMode.DIRECT_PLAY
        target_codec = None
        delivery = "direct"
        container = media.container
        reasons.append("Lecture directe : aucun traitement nécessaire.")

    # Respect des interrupteurs réseau.
    if mode == PlaybackMode.DIRECT_PLAY and not cfg.network.direct_play_enabled:
        mode = PlaybackMode.DIRECT_STREAM
    if mode == PlaybackMode.DIRECT_STREAM and not cfg.network.direct_stream_enabled:
        mode = PlaybackMode.TRANSCODE
        delivery = cfg.network.delivery
        container = "ts" if delivery == "hls" else "mkv"

    return PlaybackDecision(
        mode=mode,
        reasons=reasons,
        video_action="transcode" if mode == PlaybackMode.TRANSCODE else "copy",
        audio_action="transcode"
        if (audio_needs_transcode or mode == PlaybackMode.TRANSCODE)
        else "copy",
        tone_map=tone_map,
        target_video_codec=target_codec,
        target_audio_codec=cfg.audio.target_codec
        if (audio_needs_transcode or mode == PlaybackMode.TRANSCODE)
        else None,
        target_bitrate_mbps=cap_mbps if mode == PlaybackMode.TRANSCODE else None,
        container=container,
        delivery=delivery,
        media=media,
    )
