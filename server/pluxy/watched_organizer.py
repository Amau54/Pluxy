"""Optional physical filing of watched media; catalogue identity stays stable."""
from __future__ import annotations

import json
import math
import os
from pathlib import Path
import threading
import time

from .library_index import media_fingerprint
from .paths import atomic_write_text
from .profiles import registry

SIDECAR_EXTENSIONS = {'.srt', '.ass', '.ssa', '.vtt', '.sub', '.idx', '.nfo'}


def rename_without_overwrite(source: Path, target: Path):
    if os.name == 'nt':
        source.rename(target)  # Windows rename fails if the destination exists.
    else:
        os.link(source, target, follow_symlinks=False)
        try:
            source.unlink()
        except OSError:
            target.unlink()
            raise


class WatchedOrganizer:
    def __init__(self, state, directory: Path, *, start_worker=True):
        self.state = state
        self.directory = directory / 'watched-moves'
        self.path = directory / 'watched-organization.json'
        self.lock = threading.RLock()
        self.closed = threading.Event()
        self.wake = threading.Event()
        self.retry = {}
        self.since = time.time()
        self.moved = 0
        self.error = ''
        self.thread = None
        if self.path.exists():
            try:
                if self.path.stat().st_size > 262144:
                    raise ValueError('Suivi trop volumineux')
                data = json.loads(self.path.read_text(encoding='utf-8'))
                since, moved = float(data['since']), int(data['moved'])
                if not math.isfinite(since) or since < 0 or not 0 <= moved <= 1_000_000_000:
                    raise ValueError('Suivi invalide')
                self.since, self.moved = since, moved
            except (OSError, ValueError, KeyError, TypeError):
                self.error = 'Suivi du rangement illisible. Désactivez puis réactivez l’option pour le réinitialiser.'
        if start_worker:
            self.thread = threading.Thread(target=self._run, name='pluxy-watched-folders', daemon=True)
            self.thread.start()

    def available(self):
        try:
            return len(registry(self.state).list()) == 1
        except (OSError, ValueError, TypeError, AttributeError):
            self.error = 'Les profils sont illisibles. Le rangement automatique est suspendu.'
            return False

    def enabled(self):
        return self.state.cfgm.cfg.server.organize_watched and self.available()

    def _save(self):
        atomic_write_text(self.path, json.dumps({'since': self.since, 'moved': self.moved}))

    def configure(self, include_existing: bool):
        with self.lock:
            self.since = 0 if include_existing else time.time()
            self._save()
            self.retry.clear()
            self.error = ''
        self.wake.set()

    def _root(self, path: Path):
        for value in sorted(self.state.cfgm.cfg.server.media_dirs, key=len, reverse=True):
            root = Path(value).resolve()
            try:
                path.resolve().relative_to(root)
                return root
            except ValueError:
                continue
        raise ValueError('Le média ne se trouve plus dans un dossier partagé.')

    def _filed(self, item):
        return any(part.casefold() == 'vu' for part in Path(item.path).parts[:-1])

    def candidates(self, *, include_existing=False):
        _, watch = self.state.watch.snapshot()
        since = 0 if include_existing else self.since
        return [item for item_id, record in watch.items() if record.get('watched')
                and (item := self.state.library.get(item_id)) and item.kind in {'movie', 'series'}
                and record.get('last_played_at', 0) >= since and not self._filed(item)]

    def status(self):
        return {'available': self.available(), 'enabled': self.enabled(), 'moved': self.moved, 'error': self.error}

    def _plan(self, item):
        source = Path(item.path)
        root = self._root(source)
        if source.is_symlink() or not source.is_file() or self._filed(item):
            raise ValueError('Ce média ne peut pas être rangé automatiquement.')
        stat = source.stat()
        if stat.st_size != item.size or stat.st_mtime != item.modified_at:
            raise BlockingIOError('Le média a changé ; une analyse est nécessaire.')
        target = source.parent / 'Vu'
        if target.is_symlink() or (target.exists() and target.resolve() != source.parent.resolve() / 'Vu'):
            raise ValueError('Le dossier Vu doit être un dossier ordinaire à côté du média.')
        target.resolve().relative_to(root)
        files = [path for path in source.parent.iterdir() if path.is_file() and path.suffix.casefold() in SIDECAR_EXTENSIONS
                 and (path.stem.casefold() == source.stem.casefold() or path.stem.casefold().startswith(source.stem.casefold() + '.'))]
        files.append(source)
        if len(files) > 128:
            raise ValueError('Trop de fichiers associés à ce média.')
        entries = []
        for path in files:
            destination = target / path.name
            if path.is_symlink() or destination.exists():
                raise FileExistsError('Un fichier du même nom existe déjà dans Vu.')
            fingerprint = media_fingerprint(path, path.stat().st_size)
            if not fingerprint:
                raise PermissionError('Un fichier associé est illisible.')
            entries.append({'source': str(path), 'target': str(destination), 'size': path.stat().st_size,
                            'fingerprint': fingerprint})
        return {'id': item.id, 'path': str(source), 'target': str(target / source.name), 'files': entries,
                'subtitles': item.external_subs}

    def _validate(self, plan):
        item = self.state.library.get(plan['id'])
        source, target = Path(plan['path']), Path(plan['target'])
        root = self._root(source)
        if item is None or item.kind not in {'movie', 'series'} or item.path not in {str(source), str(target)}:
            raise ValueError('Le média ne correspond plus au déplacement enregistré.')
        if target != source.parent / 'Vu' / source.name or target.parent.is_symlink():
            raise ValueError('Destination de rangement invalide.')
        target.resolve().relative_to(root)
        if target.parent.exists() and target.parent.resolve() != source.parent.resolve() / 'Vu':
            raise ValueError('Le dossier Vu ne doit pas rediriger vers un autre emplacement.')
        if not 1 <= len(plan['files']) <= 128:
            raise ValueError('Liste de fichiers invalide.')
        if not any(entry['source'] == str(source) and entry['target'] == str(target) for entry in plan['files']):
            raise ValueError('Le fichier vidéo manque au déplacement enregistré.')
        for entry in plan['files']:
            original, destination = Path(entry['source']), Path(entry['target'])
            if original.parent != source.parent or destination != target.parent / original.name:
                raise ValueError('Un fichier associé sort du dossier du média.')
            present = original if original.exists() else destination
            if present.is_symlink() or not present.is_file() or present.stat().st_size != entry['size']:
                raise ValueError('Un fichier du déplacement a changé.')
            if media_fingerprint(present, entry['size']) != entry['fingerprint']:
                raise ValueError('Un fichier du déplacement a été remplacé.')
        return item

    def _apply(self, plan, journal):
        library = self.state.library
        with library.editing_files():
            self._validate(plan)
            moved = []
            try:
                Path(plan['target']).parent.mkdir(exist_ok=True)
                for entry in plan['files']:
                    source, target = Path(entry['source']), Path(entry['target'])
                    if source.exists():
                        if target.exists() and os.path.samefile(source, target):
                            source.unlink()  # Recover a hard link interrupted before source unlink.
                        else:
                            rename_without_overwrite(source, target)
                        moved.append((source, target))
                mapping = {entry['source']: entry['target'] for entry in plan['files']}
                library.relocated(plan['id'], plan['target'], [mapping.get(value, value) for value in plan['subtitles']])
            except Exception:
                for source, target in reversed(moved):
                    try:
                        rename_without_overwrite(target, source)
                    except OSError:
                        pass  # Keep the journal; recovery never overwrites an existing file.
                if all(Path(entry['source']).is_file() for entry in plan['files']) and library.get(plan['id']).path == plan['path']:
                    journal.unlink(missing_ok=True)
                raise
            journal.unlink()
            self.moved += 1
            self.error = ''
            self._save()
            with self.state._decision_lock:
                for key in list(self.state._decisions):
                    if key[1] == plan['id']:
                        self.state._decisions.pop(key, None)

    def recover(self):
        with self.lock:
            if self.directory.exists():
                for journal in self.directory.glob('*.json'):
                    plan = None
                    try:
                        if self.state.segments.playback_active():
                            return False
                        if journal.stat().st_size > 262144:
                            raise ValueError('Déplacement enregistré trop volumineux.')
                        plan = json.loads(journal.read_text(encoding='utf-8'))
                        self._apply(plan, journal)
                    except (OSError, ValueError, KeyError, TypeError) as error:
                        self.error = str(error)
                        try:
                            item = self.state.library.get(plan['id']) if isinstance(plan, dict) else None
                            if (item and item.path == plan['path'] and plan['files']
                                    and all(Path(entry['source']).is_file() for entry in plan['files'])):
                                journal.rename(journal.with_suffix('.cancelled-' + str(time.time_ns())))
                                continue  # No source was lost; an ordinary scan can resume.
                        except (OSError, ValueError, KeyError, TypeError):
                            pass
                        return False
            return True

    def tick(self):
        if self.closed.is_set() or self.state.library.is_scanning or self.state.segments.playback_active():
            return False
        if not self.recover():
            return False
        if not self.enabled():
            return False
        for item in self.candidates():
            if self.closed.is_set() or not self.enabled() or self.state.segments.playback_active():
                return False
            if self.retry.get(item.id, 0) > time.time() or item.id in self.state.segments.pending:
                continue
            try:
                with self.state.library.editing_files():
                    plan = self._plan(item)
                    self.directory.mkdir(exist_ok=True)
                    journal = self.directory / (item.id + '.json')
                    encoded = json.dumps(plan, ensure_ascii=False)
                    if len(encoded.encode('utf-8')) > 262144:
                        raise ValueError('Trop de fichiers associés au média.')
                    atomic_write_text(journal, encoded)
                    self._apply(plan, journal)
                return True
            except (OSError, ValueError) as error:
                self.error = item.title + ' : ' + str(error)
                self.retry[item.id] = time.time() + 300
        return False

    def _run(self):
        while not self.closed.is_set():
            try:
                with self.lock:
                    progressed = self.tick()
            except Exception:
                progressed = False
                self.error = 'Rangement interrompu. Les fichiers existants sont conservés.'
            self.wake.wait(1 if progressed else 30)
            self.wake.clear()

    def close(self):
        self.closed.set()
        self.wake.set()
        if self.thread:
            self.thread.join(timeout=5)
