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
from .tools import NO_WINDOW, has_libplacebo


# ===========================================================================
#  Construction des arguments FFmpeg
# ===========================================================================
def _audio_args(decision: PlaybackDecision, cfg: PluxyConfig, all_tracks: bool = False) -> List[str]:
    """
    Audio : copie (passthrough) si le client gère la source, sinon transcodage vers
    le codec/canaux décidés (garanti audible par le client). Repli AAC universel.
    """
    mapping = "0:a?" if all_tracks else "0:a:0?"
    if decision.audio_action == "copy":
        return ["-map", mapping, "-c:a", "copy"]
    codec = decision.target_audio_codec or cfg.audio.target_codec
    ch = max(1, decision.target_audio_channels or cfg.audio.target_channels)
    if codec == "aac":
        bitrate = 192 if ch <= 2 else 384
    else:
        bitrate = cfg.audio.bitrate_kbps           # 640k AC3/EAC3 5.1
    return [
        "-map", mapping,
        "-c:a", codec,
        "-ac", str(ch),
        "-b:a", f"{bitrate}k",
    ]


def build_direct_stream_cmd(
    media: MediaInfo,
    decision: PlaybackDecision,
    cfg: PluxyConfig,
    start_time: float = 0.0,
) -> List[str]:
    """
    Direct Stream : seule la couche conteneur (et/ou l'audio) pose problème.
    La vidéo 4K HDR n'est JAMAIS touchée -> zéro lag, charge CPU/GPU quasi nulle.
    Sortie Matroska fragmentée sur stdout (pipe:1) pour streaming progressif.
    """
    cmd = [
        cfg.ffmpeg.ffmpeg_path,
        "-hide_banner", "-loglevel", cfg.ffmpeg.log_level,
    ]
    if start_time > 0:
        cmd += ["-ss", f"{start_time:.3f}"]
    cmd += [
        "-i", media.path,
        "-map", "0:v:0",
        "-c:v", "copy",                # vidéo intacte (HEVC/HDR10 préservé)
        *_audio_args(decision, cfg),
        "-map", "0:s?", "-c:s", "copy",
        # Keep video keyframe preroll before zero. Shifting it to zero would
        # delay audio and make the player's requested position run ahead.
        "-avoid_negative_ts", "disabled",
        "-f", "matroska",
        "-",
    ]
    return cmd


def _tone_map_chain(cfg: PluxyConfig) -> tuple[str, bool]:
    """
    Tone mapping HDR10 (PQ/BT.2020) -> SDR (BT.709). Décodage NVDEC (frames CUDA) :
    on rapatrie en p010le puis on traite. Retourne (filtre, besoin_device_vulkan).

    - Si libplacebo dispo : tone mapping GPU Vulkan haute qualité (bt.2390 + détection
      du pic de luminance + correction de gamut) — rendu nettement plus naturel.
    - Sinon : repli zscale/tonemap (désaturation des hautes lumières activée).
    La chaîne se termine en yuv420p système ; NVENC encode directement (pas de re-upload).
    """
    tc = cfg.transcoding
    if tc.hardware_acceleration and has_libplacebo(cfg):
        chain = (
            "hwdownload,format=p010le,hwupload,"
            "libplacebo=tonemapping=bt.2390:colorspace=bt709:color_primaries=bt709:"
            "color_trc=bt709:peak_detect=true:gamut_mode=perceptual:format=yuv420p,"
            "hwdownload,format=yuv420p"
        )
        return chain, True
    # bt.2390 est proposé par libplacebo, pas par le filtre CPU tonemap.
    # Le repli Hable reste utilisable sur les builds sans Vulkan/libplacebo.
    algorithm = "hable" if tc.tone_map_algorithm == "bt2390" else tc.tone_map_algorithm
    download = "hwdownload,format=p010le," if tc.hardware_acceleration else ""
    chain = (
        download +
        f"zscale=transfer=linear:npl={tc.tone_map_peak_nits},"
        "format=gbrpf32le,zscale=primaries=bt709,"
        f"tonemap=tonemap={algorithm}:desat=4.5,"
        "zscale=transfer=bt709:matrix=bt709:range=tv,format=yuv420p"
    )
    return chain, False


def build_transcode_cmd(
    media: MediaInfo,
    decision: PlaybackDecision,
    cfg: PluxyConfig,
    out_dir: Path,
    start_time: float = 0.0,
    compat: bool = False,
    segment_out: Path | None = None,
    seg_duration: float | None = None,
    hls_session: bool = False,
    start_number: int = 0,
    direct_stream: bool = False,
) -> List[str]:
    """
    Transcode matériel NVENC.

    - segment_out=None : sortie HLS live (héritage).
    - segment_out=Path : produit UN segment mpegts indépendant de `seg_duration`
      secondes à partir de `start_time` -> HLS VOD à la demande (barre = film entier,
      seek partout). mpegts autonome (pas d'init partagé), décodable seul.
    """
    tc = cfg.transcoding
    cap_mbps = decision.target_bitrate_mbps or tc.max_bitrate_mbps
    bufsize = int(cap_mbps * tc.vbv_bufsize_factor)
    v = media.video
    src_hdr = bool(v and v.is_hdr)

    # ---- Détermine la chaîne de filtres + le besoin d'un device Vulkan ---- #
    vf: List[str] = []
    needs_vulkan = False
    do_tonemap = decision.tone_map or (compat and src_hdr)
    if do_tonemap:
        chain, needs_vulkan = _tone_map_chain(cfg)
        vf.append(chain)
        if compat and not decision.target_height:
            vf.append("scale=-2:1080")               # après tonemap (frames système)
    elif compat and tc.hardware_acceleration and not decision.target_height:
        vf.append("scale_cuda=-2:1080:format=nv12")  # SDR : downscale GPU
    elif compat and not decision.target_height:
        vf.append("scale=-2:1080,format=yuv420p")

    if decision.target_width and decision.target_height:
        width, height = decision.target_width, decision.target_height
        if tc.hardware_acceleration and not do_tonemap:
            pixel = "p010le" if src_hdr and not compat else "nv12"
            vf.append(f"scale_cuda={width}:{height}:format={pixel}")
        else:
            vf.append(f"scale={width}:{height}")

    cmd: List[str] = [
        cfg.ffmpeg.ffmpeg_path,
        "-hide_banner", "-loglevel", cfg.ffmpeg.log_level,
    ]
    # Device Vulkan pour libplacebo (doit être initialisé avant l'entrée).
    if needs_vulkan:
        cmd += ["-init_hw_device", "vulkan=vk:0", "-filter_hw_device", "vk"]

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

    if segment_out is not None and seg_duration:
        cmd += ["-t", f"{seg_duration:.3f}"]

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
            # -forced-idr 1 : OBLIGATOIRE pour que NVENC respecte les keyframes
            # forcées (segments HLS de durée exacte). Sans ça : segments non alignés.
            cmd += ["-gpu", str(tc.gpu_index), "-forced-idr", "1"]
    elif tc.hardware_acceleration:
        cmd += [
            "-c:v", tc.encoder,                        # hevc_nvenc
            "-preset", tc.preset, "-tune", tc.tune, "-rc", tc.rc_mode,
            "-b:v", f"{cap_mbps}M", "-maxrate", f"{cap_mbps}M", "-bufsize", f"{bufsize}M",
            "-spatial-aq", "1", "-rc-lookahead", "20", "-gpu", str(tc.gpu_index),
            "-forced-idr", "1",                        # keyframes forcées respectées
        ]
        # PAS de `-pix_fmt p010le` (échec conversion CPU sur frames CUDA).
        # Profil main10 + signalisation HDR10 COMPLÈTE si la source est HDR (HDR -> HDR) :
        # la TV bascule en mode HDR. Il ne suffit PAS des primaires/transfert : sans les
        # métadonnées de mastering (SEI), beaucoup de TV (dont Philips) restent en SDR.
        if not decision.tone_map:
            pix = (v.pix_fmt or "") if v else ""
            if v and (v.is_hdr or "10" in pix):
                cmd += ["-profile:v", "main10"]
            if v and v.is_hdr:
                cmd += ["-color_primaries", "bt2020",
                        "-color_trc", v.color_transfer or "smpte2084",
                        "-colorspace", "bt2020nc",
                        "-color_range", "tv"]
                # Métadonnées HDR10 statiques (mastering display + content light level).
                # Insérées en SEI par hevc_nvenc -> déclenche le mode HDR de la TV.
                # NVENC receives mastering/light-level data from decoded frame side
                # data. These are not FFmpeg command-line options.
                cmd += ["-extra_sei", "1"]
    else:
        cmd += [
            "-c:v", "libx265", "-preset", "fast",
            "-b:v", f"{cap_mbps}M", "-maxrate", f"{cap_mbps}M", "-bufsize", f"{bufsize}M",
        ]
        if src_hdr and not do_tonemap:
            params = ["hdr10=1", "repeat-headers=1"]
            if v.master_display:
                params.append(f"master-display={v.master_display}")
            if v.max_cll:
                params.append(f"max-cll={v.max_cll}")
            cmd += ["-pix_fmt", "yuv420p10le", "-color_primaries", "bt2020",
                    "-color_trc", v.color_transfer or "smpte2084", "-colorspace", "bt2020nc",
                    "-x265-params", ":".join(params)]

    if do_tonemap:
        # Signale la conversion au lecteur au lieu de laisser des tags HDR hérités.
        cmd += ["-color_primaries", "bt709", "-color_trc", "bt709",
                "-colorspace", "bt709", "-color_range", "tv"]

    # ---- Audio ----------------------------------------------------------- #
    if compat and not direct_stream:
        cmd += ["-map", "0:a:0?", "-c:a", "aac", "-ac", "2", "-b:a", "192k"]
    else:
        cmd += _audio_args(decision, cfg, all_tracks=direct_stream)

    # ---- Sortie ---------------------------------------------------------- #
    if direct_stream:
        cmd += ["-map", "0:s?", "-c:s", "copy", "-avoid_negative_ts", "disabled",
                "-max_interleave_delta", "100000", "-flush_packets", "1",
                "-cluster_time_limit", "1000", "-f", "matroska", "pipe:1"]
        return cmd
    if hls_session:
        # SESSION CONTINUE (façon Plex/Jellyfin) : un seul FFmpeg produit des segments
        # mpegts PARFAITEMENT alignés (keyframes forcées toutes les T s) -> aucune
        # coupure son/vidéo aux frontières. `-output_ts_offset` + `-start_number`
        # placent la session sur la bonne position de la timeline (pour le seek).
        T = seg_duration or float(cfg.network.hls_segment_duration)
        cmd += [
            "-output_ts_offset", f"{start_time:.3f}",
            "-force_key_frames", f"expr:gte(t,n_forced*{T})",
            "-f", "hls",
            "-hls_time", f"{T}",
            "-hls_list_size", "0",
            "-hls_flags", "independent_segments+temp_file",
            "-hls_segment_type", "mpegts",
            "-start_number", str(start_number),
            "-hls_segment_filename", str(out_dir / "seg_%05d.ts"),
            str(out_dir / "_live.m3u8"),
        ]
        return cmd

    if segment_out is not None:
        # Un segment mpegts autonome (héritage VOD à la demande).
        cmd += [
            "-output_ts_offset", f"{start_time:.3f}",
            "-muxdelay", "0", "-muxpreload", "0",
            "-f", "mpegts",
            str(segment_out),
        ]
        return cmd

    # Héritage : HLS fMP4 live.
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
        self._errlog = None
        self.created = time.time()
        self.last_access = time.time()

    def start(self) -> None:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        # stderr REDIRIGÉ VERS UN FICHIER (jamais un PIPE non lu) : sinon le buffer
        # du pipe se remplit -> FFmpeg bloque en écriture -> aucun segment -> deadlock.
        self._errlog = open(self.out_dir / "ffmpeg.log", "wb")
        # CWD = dossier de sortie : le segment d'init fMP4 (`init.mp4`, nom relatif)
        # est ainsi écrit AU BON ENDROIT et non dans le répertoire courant du serveur.
        try:
            self.proc = subprocess.Popen(
                self.cmd,
                stdout=subprocess.DEVNULL,
                stderr=self._errlog,
                cwd=str(self.out_dir),
                creationflags=NO_WINDOW,
            )
        except Exception:
            self._errlog.close()
            self._errlog = None
            raise

    def is_alive(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

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
                try:
                    self.proc.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    pass
        if self._errlog:
            try:
                self._errlog.close()
            except Exception:
                pass
            self._errlog = None
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
        self._closed = False

    def get_or_start(self, session_id: str, cmd_builder) -> TranscodeSession:
        """
        Retourne la session vivante pour `session_id`, ou en crée une nouvelle —
        le tout ATOMIQUEMENT sous verrou (évite la course « deux requêtes HLS
        simultanées lancent/tuent deux FFmpeg » -> 503 aléatoires + double GPU).
        """
        with self._lock:
            if self._closed:
                raise RuntimeError("Le serveur est en cours d'arrêt")
            existing = self._sessions.get(session_id)
            if existing and existing.is_alive():
                existing.last_access = time.time()
                return existing
            if existing:                       # session morte : on la remplace
                existing.stop()
                self._sessions.pop(session_id, None)

            out_dir = self.base / session_id
            sess = TranscodeSession(session_id, out_dir, cmd_builder(out_dir))
            sess.start()
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

    def stop_prefix(self, prefix: str) -> None:
        """Arrête toutes les sessions dont l'id commence par `prefix`."""
        with self._lock:
            ids = [sid for sid in self._sessions if sid.startswith(prefix)]
            victims = [self._sessions.pop(sid) for sid in ids]
        for s in victims:
            try:
                s.stop()
            except Exception:
                pass

    def stop_others(self, prefix: str, keep: str) -> None:
        """Arrête les sessions `prefix*` sauf `keep` (un seul flux de seek actif)."""
        with self._lock:
            ids = [sid for sid in self._sessions if sid.startswith(prefix) and sid != keep]
            victims = [self._sessions.pop(sid) for sid in ids]
        for s in victims:
            try:
                s.stop()
            except Exception:
                pass

    def reap_idle(self, max_idle: float = 120.0) -> None:
        """Tue les sessions inactives (client parti) pour libérer le GPU."""
        now = time.time()
        with self._lock:
            stale = [self._sessions.pop(sid)
                     for sid, s in list(self._sessions.items())
                     if now - s.last_access > max_idle]
        # stop() (terminate + rmtree) HORS du lock pour ne pas le bloquer.
        for s in stale:
            try:
                s.stop()
            except Exception:
                pass

    def stop_all(self) -> None:
        """Ferme le gestionnaire et libère tous les processus au shutdown."""
        with self._lock:
            self._closed = True
            sessions = list(self._sessions.values())
            self._sessions.clear()
        for session in sessions:
            try:
                session.stop()
            except Exception:
                pass
