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
import hashlib
import json
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path
from typing import Dict, Optional, Tuple
from weakref import WeakValueDictionary

from .config import PluxyConfig
from .models import MediaInfo, PlaybackDecision
from .tools import NO_WINDOW
from .transcoder import build_transcode_cmd


class VodSession:
    def __init__(self, out_dir: Path, cmd: list, start_seg: int, on_exit, resource_ok=None):
        self.out_dir = out_dir
        self.cmd = cmd
        self.start_seg = start_seg
        self._on_exit = on_exit
        self.proc: Optional[subprocess.Popen] = None
        self._errlog = None
        self._finish_lock = threading.Lock()
        self._finished = False
        self.last_access = time.monotonic()
        self.resource_ok = resource_ok

    def start(self) -> None:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        try:
            # Les segments en cache restent disponibles pour les retours arrière,
            # mais la progression doit appartenir à cette nouvelle session.
            (self.out_dir / "_live.m3u8").unlink(missing_ok=True)
            self._errlog = open(self.out_dir / "ffmpeg.log", "wb")
            self.proc = subprocess.Popen(
                self.cmd, stdout=subprocess.DEVNULL, stderr=self._errlog,
                cwd=str(self.out_dir), creationflags=NO_WINDOW,
            )
        except Exception:
            self._finish()
            raise
        threading.Thread(
            target=self._watch_exit,
            name=f"pluxy-vod-{self.start_seg}",
            daemon=True,
        ).start()

    def _watch_exit(self) -> None:
        proc = self.proc
        if proc is not None:
            try:
                while proc.poll() is None:
                    try:
                        proc.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        if self.resource_ok is not None and not self.resource_ok():
                            self.stop_proc()
                            break
            except Exception:
                pass
        self._finish()

    def _finish(self) -> None:
        with self._finish_lock:
            if self._finished:
                return
            self._finished = True
            if self._errlog:
                try:
                    self._errlog.close()
                except Exception:
                    pass
                self._errlog = None
            try:
                self._on_exit()
            except Exception:
                pass

    def is_alive(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def seg(self, i: int) -> Path:
        return self.out_dir / f"seg_{i:05d}.ts"

    def produced_max(self) -> int:
        # FFmpeg publie la playlist atomiquement après chaque segment terminé.
        # Lire sa fin est borné, contrairement au glob de milliers de segments
        # toutes les 200 ms; cela ignore aussi le cache d'une session précédente.
        try:
            with (self.out_dir / "_live.m3u8").open("rb") as handle:
                handle.seek(0, 2)
                handle.seek(max(0, handle.tell() - 4096))
                tail = handle.read().decode("utf-8", errors="replace")
            segments = re.findall(r"(?:^|/)seg_(\d+)\.ts\s*$", tail, re.MULTILINE)
            return max(self.start_seg - 1, int(segments[-1])) if segments else self.start_seg - 1
        except OSError:
            return self.start_seg - 1

    def stop_proc(self) -> None:
        try:
            if self.proc and self.proc.poll() is None:
                self.proc.terminate()
                try:
                    self.proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
                    try:
                        self.proc.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        pass
        except Exception:
            pass
        finally:
            self._finish()


class VodHls:
    # Délai SANS progression de FFmpeg au-delà duquel on abandonne l'attente d'un segment.
    WAIT_TIMEOUT = 30.0
    # Plafond ABSOLU d'attente d'un segment (même si FFmpeg progresse) -> garde-fou.
    WAIT_HARD_CAP = 90.0
    # Garde-fou contre un appareil defectueux qui lancerait trop de FFmpeg.
    MAX_CONCURRENT_SESSIONS = 3
    MAX_CONTEXTS = 512
    # Cohérent avec MAX_HLS_SEGMENT_INDEX (0..100000) dans routes_stream.py.
    MAX_PLAYLIST_SEGMENTS = 100_001

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
        # Une serrure reste partagée tant qu'un détenteur ou un demandeur possède
        # une référence; elle disparaît ensuite sans fuite ni suppression prématurée.
        self._locks: WeakValueDictionary[str, threading.Lock] = WeakValueDictionary()
        self._glock = threading.Lock()
        self._session_slots = threading.BoundedSemaphore(self.MAX_CONCURRENT_SESSIONS)
        self._closed = False
        self._configs: dict[str, PluxyConfig] = {}
        self._fingerprints: dict[str, str] = {}
        self._budget_lock = threading.Lock()
        self._file_lock = threading.Lock()
        self._references: dict[Path, int] = {}
        self._pending_removals: set[Path] = set()
        self._budget_checked_at = 0.0
        self._budget_ok = True
        self._startup_cfg = cfg.model_copy(deep=True)
        self.cache_error: str | None = None
        self._purge_orphans()

    @staticmethod
    def _sid(item_id: str, variant: str, session: str = "") -> str:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", item_id) or variant not in {"main", "compat"}:
            raise ValueError("Identifiant de contexte invalide")
        suffix = "_" + hashlib.sha256(session.encode()).hexdigest()[:16] if session else ""
        return f"{item_id}_{variant}{suffix}"

    def segment_time(self, item_id: str, variant: str, session: str = "") -> float:
        with self._glock:
            cfg = self._configs.get(self._sid(item_id, variant, session))
            return float(cfg.network.hls_segment_duration) if cfg else self.seg_time

    def _owned_directory(self, path: Path) -> bool:
        try:
            if path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction()):
                return False
            path.resolve().relative_to(self.base)
            marker = path / ".pluxy-session.json"
            owned_marker = False
            if marker.is_file() and marker.stat().st_size <= 4096:
                record = json.loads(marker.read_text(encoding="utf-8"))
                owned_marker = isinstance(record, dict) and record.get("app") == "pluxy" and record.get("version") == 1
            return path.parent == self.base and path.is_dir() and (
                owned_marker
                or bool(re.fullmatch(r"vods_[a-f0-9]{16}_(?:main|compat)", path.name))
            )
        except (OSError, ValueError):
            return False

    def _remove_directory(self, path: Path) -> None:
        with self._file_lock:
            if self._references.get(path, 0):
                self._pending_removals.add(path)
                return
            if self._owned_directory(path):
                shutil.rmtree(path, ignore_errors=True)

    def pin_segment(self, path: Path) -> bool:
        with self._file_lock:
            if not path.is_file():
                return False
            self._references[path.parent] = self._references.get(path.parent, 0) + 1
            return True

    def release_segment(self, path: Path) -> None:
        remove = False
        with self._file_lock:
            directory = path.parent
            count = self._references.get(directory, 0) - 1
            if count > 0:
                self._references[directory] = count
            else:
                self._references.pop(directory, None)
                remove = directory in self._pending_removals
                self._pending_removals.discard(directory)
        if remove:
            self._remove_directory(directory)

    @staticmethod
    def _process_alive(pid: int) -> bool:
        if pid <= 0:
            return False
        if os.name == "nt":
            import ctypes
            from ctypes import wintypes
            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel.OpenProcess.restype = wintypes.HANDLE
            kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
            kernel.CloseHandle.argtypes = [wintypes.HANDLE]
            handle = kernel.OpenProcess(0x1000, False, pid)
            if not handle:
                return ctypes.get_last_error() != 87  # Unknown PID; access denied is conservative.
            code = wintypes.DWORD()
            try:
                return not kernel.GetExitCodeProcess(handle, ctypes.byref(code)) or code.value == 259
            finally:
                kernel.CloseHandle(handle)
        try:
            os.kill(pid, 0)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            return True

    def _purge_orphans(self) -> None:
        for path in self.base.glob("vods_*"):
            marker = path / ".pluxy-session.json"
            # Older, unmarked directories cannot be distinguished safely from
            # another running server. Retain them; the free-space guard still applies.
            if not marker.is_file():
                continue
            if not self._owned_directory(path):
                continue
            if marker.is_file():
                try:
                    owner = json.loads(marker.read_text(encoding="utf-8")).get("owner_pid", 0)
                    if isinstance(owner, int) and self._process_alive(owner):
                        continue
                except (OSError, ValueError, AttributeError):
                    continue
            self._remove_directory(path)

    def _resource_available(self, cfg: PluxyConfig | None = None, force: bool = False) -> bool:
        cfg = cfg or self._startup_cfg
        with self._budget_lock:
            now = time.monotonic()
            if not force and now - self._budget_checked_at < 2:
                return self._budget_ok
            total = 0
            try:
                for directory in self.base.glob("vods_*"):
                    if self._owned_directory(directory):
                        for path in directory.rglob("*"):
                            if not path.is_symlink() and path.is_file():
                                total += path.stat().st_size
                free = shutil.disk_usage(self.base).free
                self._budget_ok = (total < cfg.server.transcode_cache_quota_mb * 1024**2
                                   and free > cfg.server.transcode_min_free_mb * 1024**2)
                self.cache_error = None if self._budget_ok else "Espace de transcodage insuffisant"
            except OSError:
                self._budget_ok = False
                self.cache_error = "Cache de transcodage inaccessible"
            self._budget_checked_at = now
            return self._budget_ok

    # -- Contexte --------------------------------------------------------- #
    def register(self, item_id: str, variant: str, media: MediaInfo,
                 decision: PlaybackDecision, session: str = "", cfg: PluxyConfig | None = None) -> None:
        cfg = (cfg or self._startup_cfg).model_copy(deep=True)
        try:
            source = Path(media.path).stat()
            stamp = [source.st_mtime_ns, source.st_size]
        except OSError:
            stamp = [0, media.size]
        fingerprint = hashlib.sha256(json.dumps([
            media.model_dump(), decision.model_dump(exclude={"stream_url"}), cfg.model_dump(), stamp,
        ], sort_keys=True).encode()).hexdigest()[:16]
        sid = self._sid(item_id, variant, session)
        with self._lock(sid):
            with self._glock:
                changed = sid in self._ctx and self._fingerprints.get(sid) != fingerprint
                existing = self._sessions.pop(sid, None) if changed else None
                old_dir = self._dir(sid) if changed else None
            if existing:
                existing.stop_proc()
            if old_dir:
                self._remove_directory(old_dir)
            self._register(sid, media, decision, cfg, fingerprint)

    def _register(self, sid, media, decision, cfg, fingerprint):
        with self._glock:
            if self._closed:
                return
            self._ctx[sid] = (media.model_copy(deep=True), decision.model_copy(deep=True))
            self._configs[sid] = cfg
            self._fingerprints[sid] = fingerprint
            while len(self._ctx) > self.MAX_CONTEXTS:
                oldest = next(
                    (
                        key for key in self._ctx
                        if key not in self._sessions or not self._sessions[key].is_alive()
                    ),
                    None,
                )
                if oldest is None:
                    break
                self._ctx.pop(oldest, None)
                self._configs.pop(oldest, None)
                self._fingerprints.pop(oldest, None)

    def _context(self, item_id: str, variant: str, session: str = ""):
        with self._glock:
            return self._ctx.get(self._sid(item_id, variant, session))

    # -- Playlist VOD ----------------------------------------------------- #
    def playlist(self, duration: float, segment_time: float | None = None, session: str = "") -> str:
        T = segment_time or self.seg_time
        if not math.isfinite(duration) or duration <= 0:
            total = T
        else:
            total = min(max(duration, T), T * self.MAX_PLAYLIST_SEGMENTS)
        n = max(1, math.ceil(total / T))
        out = ["#EXTM3U", "#EXT-X-VERSION:3",
               f"#EXT-X-TARGETDURATION:{int(math.ceil(T))}",
               "#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-PLAYLIST-TYPE:VOD"]
        for i in range(n):
            dur = T if i < n - 1 else (total - (n - 1) * T)
            out.append(f"#EXTINF:{dur:.3f},")
            out.append(f"seg_{i:05d}.ts" + (f"?session={session}" if session else ""))
        out.append("#EXT-X-ENDLIST")
        return "\n".join(out) + "\n"

    # -- Segment (session continue) --------------------------------------- #
    def _dir(self, sid: str) -> Path:
        fingerprint = self._fingerprints.get(sid)
        return self.base / (f"vods_{sid}_{fingerprint}" if fingerprint else f"vods_{sid}")

    def _lock(self, sid: str) -> threading.Lock:
        with self._glock:
            return self._locks.setdefault(sid, threading.Lock())

    def get_segment(self, cfg: PluxyConfig, item_id: str, variant: str,
                    i: int, session: str = "", cancel_event: threading.Event | None = None) -> Optional[Path]:
        if self._context(item_id, variant, session) is None:
            return None
        sid = self._sid(item_id, variant, session)
        with self._lock(sid):
            with self._glock:
                if self._closed or sid not in self._ctx:
                    return None
                cfg = self._configs.get(sid, cfg)
                out_dir = self._dir(sid)
            segment_time = float(cfg.network.hls_segment_duration)
            lookahead = int(math.ceil(max(60, cfg.client_buffer.max_buffer_ms / 1000) / segment_time)) + 8
            seg = out_dir / f"seg_{i:05d}.ts"
            if seg.exists() and seg.stat().st_size > 0:        # cache hit
                with self._glock:
                    s = self._sessions.get(sid)
                if s:
                    s.last_access = time.monotonic()
                return seg

            with self._glock:
                sess = self._sessions.get(sid)
            alive = sess.is_alive() if sess else False
            pmax = sess.produced_max() if (sess and alive) else None
            need_restart = (
                sess is None
                or (not alive)
                or (i < sess.start_seg)
                or (pmax is not None and i > pmax + lookahead)
            )
            if need_restart:
                if not self._resource_available(cfg, force=True):
                    return None
                if sess:
                    sess.stop_proc()
                with self._glock:
                    if sid not in self._sessions and len(self._sessions) >= self.MAX_CONTEXTS:
                        return None
                if not self._session_slots.acquire(blocking=False):
                    return None

                def release_session() -> None:
                    self._session_slots.release()

                try:
                    sess = self._make_session(
                        cfg,
                        item_id,
                        variant,
                        out_dir,
                        i,
                        release_session,
                        session,
                    )
                except Exception:
                    self._session_slots.release()
                    return None
                if sess is None:
                    self._session_slots.release()
                    return None
                with self._glock:
                    if self._closed:
                        self._session_slots.release()
                        return None
                    self._sessions[sid] = sess
                try:
                    sess.start()
                except Exception:
                    with self._glock:
                        if self._sessions.get(sid) is sess:
                            self._sessions.pop(sid, None)
                    return None
            sess.last_access = time.monotonic()
            target = sess.seg(i)
            proc_sess = sess

        return self._wait(proc_sess, target, cancel_event)

    def _make_session(
        self,
        cfg,
        item_id,
        variant,
        out_dir,
        start_seg,
        on_exit,
        session="",
    ) -> Optional[VodSession]:
        ctx = self._context(item_id, variant, session)
        if ctx is None:
            return None
        media, decision = ctx
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / ".pluxy-session.json").write_text(json.dumps(
            {"app": "pluxy", "version": 1, "owner_pid": os.getpid()}
        ), encoding="utf-8")
        segment_time = float(cfg.network.hls_segment_duration)
        cmd = build_transcode_cmd(
            media, decision, cfg, out_dir,
            start_time=start_seg * segment_time,
            compat=(variant == "compat"),
            seg_duration=segment_time,
            hls_session=True, start_number=start_seg,
        )
        return VodSession(out_dir, cmd, start_seg, on_exit, lambda: self._resource_available(cfg))

    def _wait(self, sess: VodSession, target: Path, cancel_event: threading.Event | None = None) -> Optional[Path]:
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

        now = time.monotonic()
        hard_deadline = now + self.WAIT_HARD_CAP
        stall_deadline = now + self.WAIT_TIMEOUT
        last_pmax = sess.produced_max()
        while time.monotonic() < hard_deadline:
            if cancel_event is not None and cancel_event.is_set():
                return None
            if ready():
                return target
            if not sess.is_alive():
                # Le process s'est arrêté ; laisse un court instant pour le flush final.
                time.sleep(0.3)
                return target if ready() else None
            now = time.monotonic()
            pmax = sess.produced_max()
            if pmax > last_pmax:                 # FFmpeg avance -> on prolonge l'attente
                last_pmax = pmax
                stall_deadline = now + self.WAIT_TIMEOUT
            elif now > stall_deadline:           # bloqué trop longtemps sans progresser
                break
            time.sleep(0.2)
        if ready():
            return target
        sess.stop_proc()
        return None

    # -- Nettoyage -------------------------------------------------------- #
    def cleanup_item(self, item_id: str, session: str = "") -> None:
        for variant in ("main", "compat"):
            sid = self._sid(item_id, variant, session)
            lock = self._lock(sid)
            with lock:
                with self._glock:
                    out_dir = self._dir(sid)
                    self._ctx.pop(sid, None)
                    sess = self._sessions.pop(sid, None)
                if sess:
                    sess.stop_proc()
                self._remove_directory(out_dir)
                self._configs.pop(sid, None)
                self._fingerprints.pop(sid, None)

    def reap_idle(self, max_idle: float = 900.0) -> None:
        now = time.monotonic()
        with self._glock:
            stale = [sid for sid, s in list(self._sessions.items())
                     if now - s.last_access > max_idle
                     or (not s.is_alive() and now - s.last_access > 30.0)]
        for sid in stale:
            lock = self._lock(sid)
            if not lock.acquire(blocking=False):
                continue
            try:
                with self._glock:
                    # La requête a pu toucher/recréer la session entre le relevé
                    # des candidats et l'acquisition de sa serrure.
                    current = self._sessions.get(sid)
                    if current is None or not (
                        now - current.last_access > max_idle
                        or (not current.is_alive() and now - current.last_access > 30.0)
                    ):
                        continue
                    sess = self._sessions.pop(sid, None)
                    out_dir = self._dir(sid)
                    self._ctx.pop(sid, None)
                if sess:
                    sess.stop_proc()
                self._remove_directory(out_dir)
                self._configs.pop(sid, None)
                self._fingerprints.pop(sid, None)
            finally:
                lock.release()

    def stop_all(self) -> None:
        """Interdit les nouveaux départs et arrête toutes les sessions FFmpeg."""
        with self._glock:
            self._closed = True
            ids = list(self._ctx.keys() | self._sessions.keys())
        for sid in ids:
            with self._lock(sid):
                with self._glock:
                    session = self._sessions.pop(sid, None)
                    out_dir = self._dir(sid)
                    self._ctx.pop(sid, None)
                if session:
                    session.stop_proc()
                self._remove_directory(out_dir)
        with self._glock:
            self._ctx.clear()
            self._configs.clear()
            self._fingerprints.clear()
