"""Presets shared by discovery and playback; source files are never enlarged."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path
import shutil
import subprocess

from .models import MediaInfo, PlaybackDecision, PlaybackMode
from .tools import NO_WINDOW

PROFILES = (
    ("hevc_4k", "4K · H.265", "hevc", 3840, 2160, 50),
    ("hevc_4k_light", "4K légère · H.265", "hevc", 3840, 2160, 24),
    ("hevc_1080", "1080p · H.265", "hevc", 1920, 1080, 12),
    ("h264_1080", "1080p · H.264", "h264", 1920, 1080, 10),
    ("h264_720", "720p · H.264", "h264", 1280, 720, 5),
    ("h264_480", "480p · H.264", "h264", 854, 480, 2),
)


@lru_cache(maxsize=16)
def _encoder_works(binary: str, modified: float, encoder: str, gpu: int) -> bool:
    command = [binary, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
               "color=black:size=1920x1080:rate=24", "-frames:v", "1", "-an", "-c:v", encoder]
    if encoder.endswith("_nvenc"):
        command += ["-gpu", str(gpu)]
    command += ["-f", "null", "-"]
    try:
        return subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                              timeout=3, creationflags=NO_WINDOW).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def available_profiles(cfg) -> list[dict]:
    binary = shutil.which(cfg.ffmpeg.ffmpeg_path)
    if not binary:
        return []
    modified = Path(binary).stat().st_mtime
    tc = cfg.transcoding
    supported = {}
    hardware = {}
    for codec in ("hevc", "h264"):
        encoder = "hevc_nvenc" if codec == "hevc" else "h264_nvenc"
        hardware[codec] = tc.hardware_acceleration and _encoder_works(binary, modified, encoder, tc.gpu_index)
        supported[codec] = hardware[codec] or _encoder_works(binary, modified,
            "libx265" if codec == "hevc" else "libx264", tc.gpu_index)
    return [dict(id=key, label=label, codec=codec, width=width, height=height,
                 bitrate_mbps=min(bitrate, tc.max_bitrate_mbps), hardware=hardware[codec])
            for key, label, codec, width, height, bitrate in PROFILES
            if supported[codec] and (hardware[codec] or height <= 1080)]


def conversion_decision(media: MediaInfo, original: PlaybackDecision, profile: dict, cfg) -> PlaybackDecision:
    video = media.video
    if not video or not video.width or not video.height:
        raise ValueError("Dimensions vidéo inconnues")
    ratio = min(1.0, profile["width"] / video.width, profile["height"] / video.height)
    width, height = max(2, int(video.width * ratio) // 2 * 2), max(2, int(video.height * ratio) // 2 * 2)
    source = (video.bit_rate or media.overall_bitrate or 0) / 1_000_000
    # Area-adjusted ceilings keep the lower presets lighter even on small sources.
    factor = {"hevc_4k": .85, "hevc_4k_light": .55, "hevc_1080": .40,
              "h264_1080": .32, "h264_720": .20, "h264_480": .10}[profile["id"]]
    bitrate = min(profile["bitrate_mbps"], max(.5, source * factor)) if source else profile["bitrate_mbps"]
    return original.model_copy(update=dict(
        mode=PlaybackMode.TRANSCODE, video_action="transcode", compat=profile["codec"] == "h264",
        target_video_codec=profile["codec"], target_width=width, target_height=height,
        target_bitrate_mbps=bitrate, tone_map=bool(video.is_hdr and
            (profile["codec"] == "h264" or cfg.transcoding.hdr_tone_mapping == "always")),
        delivery="conversion", media=media, profile_id=profile["id"], profile_name=profile["label"],
    ))
