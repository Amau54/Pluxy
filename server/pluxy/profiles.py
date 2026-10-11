"""Profils familiaux sans compte ni authentification."""
from __future__ import annotations

import json
import threading
import uuid
from pathlib import Path

from fastapi import HTTPException

from .paths import atomic_write_text
from .watchstate import WatchStore
from .catalogue import Catalogue


class FamilyProfiles:
    def __init__(self, data: Path):
        self.data = data
        self.path = data / "family-profiles.json"
        self.lock = threading.RLock()
        self.stores = {}
        self.names = {"default": "Général"}
        if self.path.exists():
            values = json.loads(self.path.read_text(encoding="utf-8"))
            self.names.update({key: str(name)[:40] for key, name in values.items()
                               if isinstance(key, str) and len(key) == 32 and all(c in "0123456789abcdef" for c in key)})

    def list(self):
        with self.lock:
            return [{"id": key, "name": name} for key, name in self.names.items()]

    def create(self, name: str):
        name = " ".join(name.split())
        if not name or len(name) > 40:
            raise ValueError("Choisissez un nom de 1 à 40 caractères.")
        with self.lock:
            if len(self.names) >= 12:
                raise ValueError("Maximum : douze profils.")
            key = uuid.uuid4().hex
            names = {**self.names, key: name}
            atomic_write_text(self.path, json.dumps(names, ensure_ascii=False))
            self.names = names
            return {"id": key, "name": name}

    def watch(self, key: str, default):
        with self.lock:
            if key not in self.names:
                raise HTTPException(404, "Ce profil n'existe plus. Choisissez un profil disponible.")
            if key == "default":
                return default
            if key not in self.stores:
                folder = self.data / "profiles" / key
                folder.mkdir(parents=True, exist_ok=True)
                self.stores[key] = WatchStore(folder)
            return self.stores[key]


_registry_lock = threading.Lock()


def registry(st):
    with _registry_lock:
        if not hasattr(st, "profiles"):
            st.profiles = FamilyProfiles(st.watch.path.parent)
        return st.profiles


def reader_watch(st, request):
    key = request.headers.get("x-pluxy-profile", "default")
    return st.watch if key == "default" else registry(st).watch(key, st.watch)


def reader_catalogue(st, request):
    if request.headers.get("x-pluxy-profile", "default") == "default" and hasattr(st, "catalogue"):
        return st.catalogue
    watch = reader_watch(st, request)
    if watch is st.watch:
        if not hasattr(st, "catalogue"):
            st.catalogue = Catalogue(st.library, st.metadata, watch)
        return st.catalogue
    # Bound heavyweight catalogue snapshots when several family profiles are used.
    from collections import OrderedDict
    with registry(st).lock:
        if not hasattr(st, "profile_catalogues"):
            st.profile_catalogues = OrderedDict()
        key = request.headers["x-pluxy-profile"]
        catalog = st.profile_catalogues.pop(key, None) or Catalogue(st.library, st.metadata, watch)
        st.profile_catalogues[key] = catalog
        while len(st.profile_catalogues) > 2:
            st.profile_catalogues.popitem(last=False)
        return catalog
