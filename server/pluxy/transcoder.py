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
    compat: bool = False,
) -> List[str]:
    """
    Transcode matériel NVENC -> HLS.

    Pipeline GPU pur quand pas de tone mapping (NVDEC->NVENC sans copie système) ;
    bascule download/tonemap/upload uniquement si HDR->SDR demandé.
    """
    tc = cfg.transcoding
    cap_mbps = decision.target_bitrate_mbps or tc.max_bitrate_mbps
    bufsize = int(cap_mbps * tc.vbv_bufsize_factor)
    v = media.video
    src_hdr = bool(v and v.is_hdr)

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
    if compat:
        # Mode compatibilité maximale : H.264 1080p 8-bit (décodable partout).
        # HDR -> tone mapping SDR puis downscale ; sinon downscale GPU.
        if src_hdr and tc.hardware_acceleration:
            vf.append(_tone_map_filter(cfg) + ",hwdownload,format=yuv420p,scale=-2:1080")
        elif tc.hardware_acceleration:
            vf.append("scale_cuda=-2:1080:format=nv12")
        else:
            vf.append("scale=-2:1080,format=yuv420p")
    elif decision.tone_map and tc.hardware_acceleration:
        vf.append(_tone_map_filter(cfg))
    # (Mode normal sans tone map : résolution native conservée, on bride le débit.)

    cmd += ["-map", "0:v:0"]
    if vf:
        cmd += ["-vf", ",".join(vf)]

    # ---- Encodeur vidéo -------------------------------------------------- #
    if compat:
        # H.264 high 8-bit, débit modéré : universellement décodable.
        comp_mbps = min(cap_mbps, 12)
        cmd += [
            "-c:v", "h264_nvenc" if tc.hardware_acceleration else "libx264",
            "-preset", tc.preset if tc.hardware_acceleration else "veryfast",
            "-profile:v", "high",
            "-b:v", f"{comp_mbps}M", "-maxrate", f"{comp_mbps}M",
            "-bufsize", f"{comp_mbps * 2}M",
        ]
        if tc.hardware_acceleration:
            cmd += ["-gpu", str(tc.gpu_index)]
    elif tc.hardware_acceleration:
        cmd += [
            "-c:v", tc.encoder,                        # hevc_nvenc
            "-preset", tc.preset, "-tune", tc.tune, "-rc", tc.rc_mode,
            "-b:v", f"{cap_mbps}M", "-maxrate", f"{cap_mbps}M", "-bufsize", f"{bufsize}M",
            "-spatial_aq", "1", "-rc-lookahead", "20", "-gpu", str(tc.gpu_index),
        ]
        # PAS de `-pix_fmt p010le` (échec conversion CPU sur frames CUDA).
        # Profil main10 seulement si HDR/10-bit (préserve le HDR).
        if not decision.tone_map:
            pix = (v.pix_fmt or "") if v else ""
            if v and (v.is_hdr or "10" in pix):
                cmd += ["-profile:v", "main10"]
    else:
        cmd += [
            "-c:v", "libx265", "-preset", "fast",
            "-b:v", f"{cap_mbps}M", "-maxrate", f"{cap_mbps}M", "-bufsize", f"{bufsize}M",
        ]

    # ---- Audio ----------------------------------------------------------- #
    if compat:
        cmd += ["-map", "0:a:0?", "-c:a", "aac", "-ac", "2", "-b:a", "192k"]
    else:
        cmd += _audio_args(decision, cfg)

    # ---- Sortie HLS fMP4 (CMAF) : bien plus compatible que mpegts pour HEVC #
    seg_dur = cfg.network.hls_segment_duration
    cmd += [
        "-f", "hls",
        "-hls_time", str(seg_dur),
        "-hls_list_size", str(cfg.network.hls_playlist_size),
        "-hls_playlist_type", "event",
        "-hls_segment_type", "fmp4",
        "-hls_fmp4_init_filename", "init.mp4",
        "-hls_flags", cfg.network.hls_flags,
        "-hls_segment_filename", str(out_dir / "seg_%05d.m4s"),
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
        # CWD = dossier de sortie : le segment d'init fMP4 (`init.mp4`, nom relatif)
        # est ainsi écrit AU BON ENDROIT et non dans le répertoire courant du serveur.
        self.proc = subprocess.Popen(
            self.cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            cwd=str(self.out_dir),
        )

    def playlist_path(self) -> Path:
        return self.out_dir / "index.m3u8"

    def wait_for_playlist(self, timeout: float = 25.0) -> bool:
        """
        Attend que FFmpeg écrive la playlist + le segment d'init fMP4 + au moins
        un segment média. (Segments fMP4 = `.m4s`, fallback `.ts` pour mpegts.)
        """
        deadline = time.time() + timeout
        pl = self.playlist_path()
        while time.time() < deadline:
            has_seg = any(self.out_dir.glob("seg_*.m4s")) or any(self.out_dir.glob("seg_*.ts"))
            has_init = (self.out_dir / "init.mp4").exists()
            if pl.exists() and has_seg and has_init:
                return True
            if self.proc and self.proc.poll() is not None:
                return False        # FFmpeg s'est arrêté prématurément
            time.sleep(0.15)
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
        # ABSOLU obligatoire : FFmpeg tourne avec cwd=out_dir (pour écrire init.mp4
        # au bon endroit). Un chemin relatif provoquerait des segments imbriqués
        # introuvables -> "transcodage n'a pas pu démarrer". On résout donc en absolu.
        self.base = Path(cfg.server.transcode_temp_dir).resolve()
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
