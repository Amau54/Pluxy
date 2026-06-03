"""
HLS VOD à transcodage en SESSION CONTINUE (architecture façon Plex/Jellyfin).

- Playlist VOD complète (durée connue) -> barre de lecture = film entier, seek partout.
- UN seul FFmpeg par flux produit des segments mpegts PARFAITEMENT ALIGNÉS (keyframes
  forcées) -> AUCUNE coupure son/vidéo aux frontières (contrairement au transcodage
  segment-par-segment).
- Lecture séquentielle : le segment demandé est servi dès qu'il est prêt (le transcodage
  va plus vite que le temps réel et reste en avance).
- Seek : si le segment demandé est loin devant la production -> on REDÉMARRE la session
  à cet instant (les segments déjà produits restent en cache pour les retours arrière).
"""
from __future__ import annotations

import math
import shutil
import subprocess
import threading
import time
from pathlib import Path
from typing import Dict, Optional, Tuple

from .config import PluxyConfig
from .models import MediaInfo, PlaybackDecision
from .tools import NO_WINDOW
from .transcoder import build_transcode_cmd


class VodSession:
    def __init__(self, out_dir: Path, cmd: list, start_seg: int):
        self.out_dir = out_dir
        self.cmd = cmd
        self.start_seg = start_seg
        self.proc: Optional[subprocess.Popen] = None
        self._errlog = None
        self.last_access = time.time()

    def start(self) -> None:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self._errlog = open(self.out_dir / "ffmpeg.log", "wb")
        self.proc = subprocess.Popen(
            self.cmd, stdout=subprocess.DEVNULL, stderr=self._errlog,
            cwd=str(self.out_dir), creationflags=NO_WINDOW,
        )

    def is_alive(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def seg(self, i: int) -> Path:
        return self.out_dir / f"seg_{i:05d}.ts"

    def produced_max(self) -> int:
        mx = self.start_seg - 1
        for p in self.out_dir.glob("seg_*.ts"):
            try:
                mx = max(mx, int(p.stem.split("_")[1]))
            except Exception:
                pass
        return mx

    def stop_proc(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        if self._errlog:
            try:
                self._errlog.close()
            except Exception:
                pass
            self._errlog = None


class VodHls:
    # Délai SANS progression de FFmpeg au-delà duquel on abandonne l'attente d'un segment.
    WAIT_TIMEOUT = 30.0
    # Plafond ABSOLU d'attente d'un segment (même si FFmpeg progresse) -> garde-fou.
    WAIT_HARD_CAP = 90.0

    def __init__(self, cfg: PluxyConfig, _base_dir: Path):
        self.base = Path(cfg.server.transcode_temp_dir).resolve()
        self.base.mkdir(parents=True, exist_ok=True)
        self.seg_time = float(max(2, cfg.network.hls_segment_duration))
        # Tolérance vers l'AVANT (en segments) avant de considérer une requête comme un
        # vrai seek nécessitant un redémarrage de session. Doit COUVRIR le pré-buffer
        # du client : sinon une simple lecture séquentielle bufferisée (ExoPlayer charge
        # jusqu'à max_buffer_ms d'avance) dépasse le seuil -> faux "seek" -> on tue le
        # FFmpeg qui produisait justement ces segments -> écran noir + relance. C'était
        # la cause des arrêts/rechargements rares. On dimensionne sur le buffer + marge.
        buf_s = max(60.0, cfg.client_buffer.max_buffer_ms / 1000.0)
        self.lookahead = int(math.ceil(buf_s / self.seg_time)) + 8
        self._ctx: Dict[str, Tuple[MediaInfo, PlaybackDecision]] = {}
        self._sessions: Dict[str, VodSession] = {}
        self._locks: Dict[str, threading.Lock] = {}
        self._glock = threading.Lock()

    # -- Contexte --------------------------------------------------------- #
    def register(self, item_id: str, variant: str, media: MediaInfo,
                 decision: PlaybackDecision) -> None:
        self._ctx[f"{item_id}_{variant}"] = (media, decision)

    def _context(self, item_id: str, variant: str):
        return self._ctx.get(f"{item_id}_{variant}")

    # -- Playlist VOD ----------------------------------------------------- #
    def playlist(self, duration: float) -> str:
        T = self.seg_time
        total = max(duration, T)
        n = max(1, math.ceil(total / T))
        out = ["#EXTM3U", "#EXT-X-VERSION:3",
               f"#EXT-X-TARGETDURATION:{int(math.ceil(T))}",
               "#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-PLAYLIST-TYPE:VOD"]
        for i in range(n):
            dur = T if i < n - 1 else (total - (n - 1) * T)
            out.append(f"#EXTINF:{dur:.3f},")
            out.append(f"seg_{i:05d}.ts")
        out.append("#EXT-X-ENDLIST")
        return "\n".join(out) + "\n"

    # -- Segment (session continue) --------------------------------------- #
    def _dir(self, sid: str) -> Path:
        return self.base / f"vods_{sid}"

    def _lock(self, sid: str) -> threading.Lock:
        with self._glock:
            return self._locks.setdefault(sid, threading.Lock())

    def get_segment(self, cfg: PluxyConfig, item_id: str, variant: str,
                    i: int) -> Optional[Path]:
        if self._context(item_id, variant) is None:
            return None
        sid = f"{item_id}_{variant}"
        out_dir = self._dir(sid)

        with self._lock(sid):
            seg = out_dir / f"seg_{i:05d}.ts"
            if seg.exists() and seg.stat().st_size > 0:        # cache hit
                s = self._sessions.get(sid)
                if s:
                    s.last_access = time.time()
                return seg

            sess = self._sessions.get(sid)
            alive = sess.is_alive() if sess else False
            pmax = sess.produced_max() if (sess and alive) else None
            need_restart = (
                sess is None
                or (not alive)
                or (i < sess.start_seg)
                or (pmax is not None and i > pmax + self.lookahead)
            )
            if need_restart:
                if sess:
                    sess.stop_proc()
                sess = self._make_session(cfg, item_id, variant, out_dir, i)
                if sess is None:
                    return None
                sess.start()
                self._sessions[sid] = sess
            sess.last_access = time.time()
            target = sess.seg(i)
            proc_sess = sess

        return self._wait(proc_sess, target)

    def _make_session(self, cfg, item_id, variant, out_dir, start_seg) -> Optional[VodSession]:
        ctx = self._context(item_id, variant)
        if ctx is None:
            return None
        media, decision = ctx
        out_dir.mkdir(parents=True, exist_ok=True)
        cmd = build_transcode_cmd(
            media, decision, cfg, out_dir,
            start_time=start_seg * self.seg_time,
            compat=(variant == "compat"),
            seg_duration=self.seg_time,
            hls_session=True, start_number=start_seg,
        )
        return VodSession(out_dir, cmd, start_seg)

    def _wait(self, sess: VodSession, target: Path) -> Optional[Path]:
        """
        Attend la production du segment `target`. PATIENT tant que FFmpeg est vivant ET
        PROGRESSE (nouveaux segments produits) : une requête loin devant (gros pré-buffer
        client, scène lourde qui ralentit momentanément NVENC) n'échoue plus en 503 —
        ce qui provoquait l'arrêt/écran noir. On n'abandonne que si FFmpeg meurt, stagne
        plus de WAIT_TIMEOUT sans produire, ou dépasse le plafond absolu WAIT_HARD_CAP.
        """
        def ready() -> bool:
            try:
                return target.exists() and target.stat().st_size > 0
            except OSError:
                return False

        now = time.time()
        hard_deadline = now + self.WAIT_HARD_CAP
        stall_deadline = now + self.WAIT_TIMEOUT
        last_pmax = sess.produced_max()
        while time.time() < hard_deadline:
            if ready():
                return target
            if not sess.is_alive():
                # Le process s'est arrêté ; laisse un court instant pour le flush final.
                time.sleep(0.3)
                return target if ready() else None
            now = time.time()
            pmax = sess.produced_max()
            if pmax > last_pmax:                 # FFmpeg avance -> on prolonge l'attente
                last_pmax = pmax
                stall_deadline = now + self.WAIT_TIMEOUT
            elif now > stall_deadline:           # bloqué trop longtemps sans progresser
                break
            time.sleep(0.2)
        return target if ready() else None

    # -- Nettoyage -------------------------------------------------------- #
    def cleanup_item(self, item_id: str) -> None:
        for variant in ("main", "compat"):
            sid = f"{item_id}_{variant}"
            self._ctx.pop(sid, None)
            sess = self._sessions.pop(sid, None)
            if sess:
                sess.stop_proc()
            shutil.rmtree(self._dir(sid), ignore_errors=True)

    def reap_idle(self, max_idle: float = 900.0) -> None:
        now = time.time()
        with self._glock:
            stale = [sid for sid, s in list(self._sessions.items())
                     if now - s.last_access > max_idle]
        for sid in stale:
            sess = self._sessions.pop(sid, None)
            if sess:
                sess.stop_proc()
            shutil.rmtree(self._dir(sid), ignore_errors=True)
