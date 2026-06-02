"""
Constructeur de lignes de commande FFmpeg dynamiques + gestion des sessions
(Workflow 1 & 2).

- Direct Stream : remux conteneur (+ downmix audio éventuel), copie vidéo, sortie
  fragmentée pipe -> HTTP.
- Transcode : pipeline strictement NVIDIA (NVDEC -> CUDA -> NVENC `hevc_nvenc`),
  bitrate cap, tone mapping HDR->SDR via filtres CUDA, sortie HLS segmentée pour
  absorber la latence du double saut Wi-Fi.
"""
from __future__ import annotations

import shutil
import subprocess
import threading
import time
from pathlib import Path
from typing import Dict, List, Optional

from .config import PluxyConfig
from .models import MediaInfo, PlaybackDecision


# ===========================================================================
#  Construction des arguments FFmpeg
# ===========================================================================
def _audio_args(decision: PlaybackDecision, cfg: PluxyConfig) -> List[str]:
    """Sélectionne la 1re piste audio et la downmixe si nécessaire (ARC Philips 803)."""
    if decision.audio_action == "copy":
        return ["-map", "0:a:0?", "-c:a", "copy"]
    return [
        "-map", "0:a:0?",
        "-c:a", cfg.audio.target_codec,            # ac3 (universel ARC) / eac3
        "-ac", str(cfg.audio.target_channels),     # downmix 5.1
        "-b:a", f"{cfg.audio.bitrate_kbps}k",
    ]


def build_direct_stream_cmd(
    media: MediaInfo,
    decision: PlaybackDecision,
    cfg: PluxyConfig,
) -> List[str]:
    """
    Direct Stream : seule la couche conteneur (et/ou l'audio) pose problème.
    La vidéo 4K HDR n'est JAMAIS touchée -> zéro lag, charge CPU/GPU quasi nulle.
    Sortie Matroska fragmentée sur stdout (pipe:1) pour streaming progressif.
    """
    cmd = [
        cfg.ffmpeg.ffmpeg_path,
        "-hide_banner", "-loglevel", cfg.ffmpeg.log_level,
        "-i", media.path,
        "-map", "0:v:0",
        "-c:v", "copy",                # vidéo intacte (HEVC/HDR10 préservé)
        *_audio_args(decision, cfg),
        "-map", "0:s?", "-c:s", "copy",
        "-f", "matroska",
        "-",
    ]
    return cmd


def _tone_map_filter(cfg: PluxyConfig) -> str:
    """
    Chaîne de tone mapping HDR10 (PQ) -> SDR (BT.709).

    NVDEC décode sur GPU -> on rapatrie en p010le -> zscale linéarise et applique
    l'algo de tone mapping -> reconversion BT.709 -> hwupload_cuda pour NVENC.
    """
    tc = cfg.transcoding
    return (
        "hwdownload,format=p010le,"
        f"zscale=transfer=linear:npl={tc.tone_map_peak_nits},"
        "format=gbrpf32le,"
        "zscale=primaries=bt709,"
        f"tonemap=tonemap={tc.tone_map_algorithm}:desat=0,"
        "zscale=transfer=bt709:matrix=bt709:range=tv,"
        "format=yuv420p,"
        "hwupload_cuda"
    )


def build_transcode_cmd(
    media: MediaInfo,
    decision: PlaybackDecision,
    cfg: PluxyConfig,
    out_dir: Path,
    start_time: float = 0.0,
) -> List[str]:
    """
    Transcode matériel NVENC -> HLS.

    Pipeline GPU pur quand pas de tone mapping (NVDEC->NVENC sans copie système) ;
    bascule download/tonemap/upload uniquement si HDR->SDR demandé.
    """
    tc = cfg.transcoding
    cap_mbps = decision.target_bitrate_mbps or tc.max_bitrate_mbps
    bufsize = int(cap_mbps * tc.vbv_bufsize_factor)

    cmd: List[str] = [
        cfg.ffmpeg.ffmpeg_path,
        "-hide_banner", "-loglevel", cfg.ffmpeg.log_level,
    ]

    # Décodage matériel NVDEC + frames résidentes en VRAM (CUDA).
    if tc.hardware_acceleration:
        cmd += [
            "-hwaccel", tc.decoder_hwaccel,            # cuda
            "-hwaccel_output_format", "cuda",
            "-hwaccel_device", str(tc.gpu_index),
        ]

    if start_time > 0:
        cmd += ["-ss", f"{start_time:.3f}"]

    cmd += ["-i", media.path]

    # ---- Filtres vidéo --------------------------------------------------- #
    vf: List[str] = []
    if decision.tone_map and tc.hardware_acceleration:
        vf.append(_tone_map_filter(cfg))
    # (Pas de scale ici : on conserve la résolution native 4K, on bride le débit.)

    cmd += ["-map", "0:v:0"]
    if vf:
        cmd += ["-vf", ",".join(vf)]

    # ---- Encodeur NVENC -------------------------------------------------- #
    if tc.hardware_acceleration:
        cmd += [
            "-c:v", tc.encoder,                        # hevc_nvenc
            "-preset", tc.preset,                      # p1..p7
            "-tune", tc.tune,                          # hq
            "-rc", tc.rc_mode,                         # vbr
            "-b:v", f"{cap_mbps}M",
            "-maxrate", f"{cap_mbps}M",
            "-bufsize", f"{bufsize}M",
            "-spatial_aq", "1",
            "-rc-lookahead", "20",
            "-gpu", str(tc.gpu_index),
        ]
        # IMPORTANT : ne PAS forcer `-pix_fmt p010le`. Les frames décodées résident
        # en mémoire CUDA ; un -pix_fmt déclenche une conversion CPU qui échoue
        # ("Error reinitializing filters / Function not implemented") et empêche
        # NVENC de démarrer. NVENC consomme directement le format CUDA de la source.
        # On conserve seulement le profil main10 si la source est 10 bits / HDR
        # (préserve le HDR sans conversion).
        if not decision.tone_map:
            v = media.video
            pix = (v.pix_fmt or "") if v else ""
            is_10bit = bool(v and (v.is_hdr or "10" in pix))
            if is_10bit:
                cmd += ["-profile:v", "main10"]
    else:
        # Repli logiciel (signalé par le moteur de décision) — x265.
        cmd += [
            "-c:v", "libx265",
            "-preset", "fast",
            "-b:v", f"{cap_mbps}M",
            "-maxrate", f"{cap_mbps}M",
            "-bufsize", f"{bufsize}M",
        ]

    # ---- Audio ----------------------------------------------------------- #
    cmd += _audio_args(decision, cfg)

    # ---- Sortie HLS (segments mpegts) ----------------------------------- #
    seg_dur = cfg.network.hls_segment_duration
    cmd += [
        "-f", "hls",
        "-hls_time", str(seg_dur),
        "-hls_list_size", str(cfg.network.hls_playlist_size),
        "-hls_playlist_type", "event",
        "-hls_segment_type", "mpegts",
        "-hls_flags", cfg.network.hls_flags,
        "-hls_segment_filename", str(out_dir / "seg_%05d.ts"),
        "-start_number", "0",
        str(out_dir / "index.m3u8"),
    ]
    return cmd


# ===========================================================================
#  Gestion des sessions de transcodage
# ===========================================================================
class TranscodeSession:
    def __init__(self, session_id: str, out_dir: Path, cmd: List[str]):
        self.id = session_id
        self.out_dir = out_dir
        self.cmd = cmd
        self.proc: Optional[subprocess.Popen] = None
        self.created = time.time()
        self.last_access = time.time()

    def start(self) -> None:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.proc = subprocess.Popen(
            self.cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )

    def playlist_path(self) -> Path:
        return self.out_dir / "index.m3u8"

    def wait_for_playlist(self, timeout: float = 20.0) -> bool:
        """Attend que FFmpeg écrive au moins le premier segment + la playlist."""
        deadline = time.time() + timeout
        pl = self.playlist_path()
        while time.time() < deadline:
            if pl.exists() and any(self.out_dir.glob("seg_*.ts")):
                return True
            if self.proc and self.proc.poll() is not None:
                return False        # FFmpeg s'est arrêté prématurément
            time.sleep(0.2)
        return False

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        shutil.rmtree(self.out_dir, ignore_errors=True)


class TranscodeManager:
    def __init__(self, cfg: PluxyConfig):
        self.base = Path(cfg.server.transcode_temp_dir)
        self.base.mkdir(parents=True, exist_ok=True)
        self._sessions: Dict[str, TranscodeSession] = {}
        self._lock = threading.RLock()

    def start(self, session_id: str, cmd_builder) -> TranscodeSession:
        """`cmd_builder(out_dir)` renvoie la liste d'arguments FFmpeg."""
        with self._lock:
            old = self._sessions.pop(session_id, None)
        if old:
            old.stop()

        out_dir = self.base / session_id
        cmd = cmd_builder(out_dir)
        sess = TranscodeSession(session_id, out_dir, cmd)
        sess.start()
        with self._lock:
            self._sessions[session_id] = sess
        return sess

    def get(self, session_id: str) -> Optional[TranscodeSession]:
        with self._lock:
            s = self._sessions.get(session_id)
        if s:
            s.last_access = time.time()
        return s

    def stop(self, session_id: str) -> None:
        with self._lock:
            s = self._sessions.pop(session_id, None)
        if s:
            s.stop()

    def reap_idle(self, max_idle: float = 120.0) -> None:
        """Tue les sessions inactives (client parti) pour libérer le GPU."""
        now = time.time()
        with self._lock:
            stale = [sid for sid, s in self._sessions.items()
                     if now - s.last_access > max_idle]
            for sid in stale:
                self._sessions.pop(sid).stop()
