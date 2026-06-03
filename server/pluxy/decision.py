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
from .tools import ffmpeg_available

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
    # need_compat : le lecteur ne sait PAS décoder la vidéo source
    # (codec/profil/résolution) -> transcodage H.264 1080p "compatibilité maximale".
    need_compat = False

    if tc.force_transcode:
        video_needs_transcode = True
        reasons.append("Transcodage forcé par l'utilisateur.")

    if v is None:
        reasons.append("Aucune piste vidéo détectée.")
    else:
        is_hevc = v.codec in ("hevc", "h265")
        is_10bit = bool(v.is_hdr or "10" in (v.pix_fmt or ""))
        # Le client décode-t-il réellement cette vidéo ?
        if is_hevc:
            codec_ok = client.supports_hevc and (client.supports_hevc_10bit or not is_10bit)
        elif v.codec in _VIDEO_CODEC_OK:
            codec_ok = client.supports_h264
        else:
            codec_ok = False

        if not codec_ok:
            video_needs_transcode = True
            need_compat = True
            detail = "HEVC 10 bits (Main10)" if (is_hevc and is_10bit) else v.codec
            reasons.append(f"Lecteur incompatible avec {detail} -> transcodage H.264.")

        # Résolution supérieure à ce que le lecteur décode.
        if v.height and client.max_video_height and v.height > client.max_video_height:
            video_needs_transcode = True
            need_compat = True
            reasons.append(
                f"Résolution {v.height}p > max décodable {client.max_video_height}p."
            )

        # Débit trop élevé -> transcode AVEC bitrate cap, MAIS seulement si l'utilisateur
        # a explicitement désactivé la lecture directe (sinon : qualité d'origine, façon
        # Plex en réseau local). Le client peut aussi imposer sa propre limite Wi-Fi.
        apply_cap = (not tc.prefer_direct_play) or bool(client.max_bitrate_mbps)
        effective_cap = client.max_bitrate_mbps if tc.prefer_direct_play else cap_mbps
        if apply_cap and effective_cap:
            v_bps = (v.bit_rate if v and v.bit_rate else media.overall_bitrate) or 0
            src_mbps = v_bps / 1_000_000
            if src_mbps > effective_cap:
                video_needs_transcode = True
                cap_mbps = effective_cap
                reasons.append(
                    f"Débit source {src_mbps:.0f} Mbps > plafond {effective_cap} Mbps."
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

    # ---- 3. Faut-il toucher à l'AUDIO ? (passthrough si possible) -------- #
    # On NE downmixe PLUS systématiquement : si le lecteur (ou l'ampli via HDMI/eARC)
    # sait décoder le format (DTS-HD, TrueHD, Atmos, EAC3…), on transmet le flux
    # audio TEL QUEL (bitstream / passthrough) -> qualité maximale.
    audio_needs_transcode = False
    a = media.audios[0] if media.audios else None
    if a is not None:
        client_audio = {c.lower() for c in client.supported_audio_codecs}
        client_supports = a.codec in client_audio
        too_many_ch = (a.channels or 0) > client.max_audio_channels
        # Downmix lossless seulement si le client NE sait PAS le décoder (sinon passthrough).
        if not client_supports or too_many_ch:
            audio_needs_transcode = True
            reasons.append(
                f"Audio {a.codec} {a.channels}ch non géré -> "
                f"{cfg.audio.target_codec} {cfg.audio.target_channels}ch."
            )
        else:
            reasons.append(f"Audio {a.codec} {a.channels}ch transmis tel quel (passthrough).")

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

    # Garde-fou : sans FFmpeg, ni remux ni transcode possibles -> Direct Play.
    # Le client (ExoPlayer) tente alors de décoder le fichier brut lui-même.
    if mode in (PlaybackMode.DIRECT_STREAM, PlaybackMode.TRANSCODE) and not ffmpeg_available(cfg):
        reasons.append("⚠ FFmpeg introuvable : repli Direct Play (lecture brute par le client).")
        mode = PlaybackMode.DIRECT_PLAY
        target_codec = None
        tone_map = False
        delivery = "direct"
        container = media.container
        audio_needs_transcode = False
        need_compat = False

    compat = need_compat and mode == PlaybackMode.TRANSCODE

    # ---- Action audio : GARANTIR que tous les formats audio passent ------ #
    # Règle : on COPIE l'audio (passthrough bitstream DTS-HD/TrueHD/Atmos/EAC3…) dès que
    # le client sait le restituer (décodage OU passthrough vers l'ampli). Sinon on
    # transcode vers le MEILLEUR codec réellement supporté par le client -> son garanti.
    # NB : les segments de transcodage sont en mpegts, qui porte TOUS les codecs (copie
    # possible même en transcodage vidéo).
    client_audio = {c.lower() for c in client.supported_audio_codecs}
    src_ch = (a.channels if (a and a.channels) else 6)

    def best_audio_target() -> tuple[str, int]:
        ch_max = max(2, client.max_audio_channels)
        if "eac3" in client_audio:
            return "eac3", min(src_ch, ch_max, 8)
        if "ac3" in client_audio:
            return "ac3", min(src_ch, ch_max, 6)
        return "aac", min(src_ch, ch_max, 8)       # AAC : décodable partout (repli sûr)

    # Pas de piste audio -> rien à faire (copy). Sinon : le client gère-t-il la source ?
    client_plays_audio = (a is None) or (
        a.codec in client_audio and src_ch <= max(2, client.max_audio_channels)
    )

    if compat:
        audio_action = "transcode"                 # compat = AAC stéréo universel
        tgt_codec, tgt_ch = "aac", 2
    elif client_plays_audio:
        audio_action = "copy"                      # passthrough / lecture native
        tgt_codec, tgt_ch = None, src_ch
    else:
        audio_action = "transcode"                 # vers un codec garanti audible
        tgt_codec, tgt_ch = best_audio_target()
        reasons.append(f"Audio {a.codec if a else '?'} -> {tgt_codec} {tgt_ch}ch (compatibilité).")

    return PlaybackDecision(
        mode=mode,
        reasons=reasons,
        compat=compat,
        video_action="transcode" if mode == PlaybackMode.TRANSCODE else "copy",
        audio_action=audio_action,
        tone_map=tone_map,
        target_video_codec=target_codec,
        target_audio_codec=tgt_codec if audio_action == "transcode" else None,
        target_audio_channels=tgt_ch,
        target_bitrate_mbps=cap_mbps if mode == PlaybackMode.TRANSCODE else None,
        container=container,
        delivery=delivery,
        media=media,
    )
