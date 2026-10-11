import json
import sqlite3
from pathlib import Path

from test_catalogue_organization import make_state
from pluxy.library import Library
from pluxy.library_index import LibraryIndex
from pluxy.models import MediaItem, MovieMetadata
from pluxy.profiles import FamilyProfiles
from pluxy.watchstate import WatchStore
from pluxy import filename
import pytest


def test_failed_first_sqlite_save_does_not_hide_legacy_index(tmp_path):
    class BrokenItem:
        def model_dump(self):
            raise RuntimeError("write failed")

    index = LibraryIndex(tmp_path / "library.sqlite3")
    with pytest.raises(RuntimeError):
        index.save({"broken": BrokenItem()})
    assert index.load() is None


def test_shared_android_pc_filename_contract():
    cases = json.loads((Path(__file__).resolve().parents[2] / "assets/contracts/filenames.json").read_text(encoding="utf-8"))
    for row in cases:
        parsed = filename.parse(row["file"])
        assert (parsed.title, parsed.year) == (row["title"], row["year"])


def test_corrupt_index_is_preserved_and_rescannable(tmp_path, monkeypatch):
    state, root, _ = make_state(tmp_path, monkeypatch)
    (root / "Movie.mkv").write_bytes(b"movie")
    state.library.scan()
    state.library.index_path.write_bytes(b"damaged database")
    restored = Library(state.cfgm, tmp_path)
    assert list(tmp_path.glob("library.sqlite3.invalid-*"))
    restored.scan()
    assert len(restored.all()) == 1


def test_invalid_sqlite_row_does_not_erase_valid_media(tmp_path):
    index = LibraryIndex(tmp_path / "library.sqlite3")
    item = MediaItem(id="valid", title="Photo", path="/photo", size=1, duration=0, container="jpg", kind="photo")
    index.save({item.id: item})
    with sqlite3.connect(index.path) as db:
        db.execute("INSERT INTO media VALUES ('bad','{')")
    assert [row["id"] for row in index.load()] == ["valid"]


def test_move_keeps_identity_resume_and_metadata(tmp_path, monkeypatch):
    state, root, probes = make_state(tmp_path, monkeypatch)
    original = root / "Original.mkv"
    original.write_bytes(b"my unique movie")
    state.library.scan()
    first = state.library.all()[0]
    state.watch.set(first.id, 42_000, 120_000)
    state.metadata._save(first.id, MovieMetadata(title="Mon film", matched=True))
    folder = root / "Nouveau dossier"
    folder.mkdir()
    target = folder / "Renommé.mkv"
    original.rename(target)
    state.library.scan()
    updated = state.library.all()[0]
    assert updated.id == first.id and updated.path == str(target)
    assert updated.title == "Renommé" and updated.added_at == first.added_at
    assert len(probes) == 1
    assert state.watch.get(updated.id)["position_ms"] == 42_000
    assert state.metadata.cached(updated.id).title == "Mon film"
    restored = Library(state.cfgm, tmp_path)
    assert restored.get(first.id).path == str(target)


def test_identical_existing_files_remain_separate(tmp_path, monkeypatch):
    state, root, _ = make_state(tmp_path, monkeypatch)
    (root / "A.mkv").write_bytes(b"identical")
    (root / "B.mkv").write_bytes(b"identical")
    state.library.scan()
    assert len({item.id for item in state.library.all()}) == 2


def test_transactional_index_above_old_limit(tmp_path):
    index = LibraryIndex(tmp_path / "library.sqlite3")
    items = {str(i): MediaItem(id=str(i), title=str(i), path=f"/{i}", size=1, duration=0, container="jpg", kind="photo") for i in range(10_025)}
    index.save(items)
    assert len(index.load()) == 10_025
    items.pop("23")
    items["24"] = items["24"].model_copy(update={"title": "Changed"})
    index.save(items)
    loaded = {row["id"]: row for row in index.load()}
    assert "23" not in loaded and loaded["24"]["title"] == "Changed"


def test_family_profiles_keep_old_history_and_isolate_new(tmp_path):
    old = WatchStore(tmp_path)
    old.set("film", 12_000, 120_000)
    profiles = FamilyProfiles(tmp_path)
    assert profiles.names["default"] == "Général"
    alice = profiles.create("Alice")
    bob = profiles.create("Bob")
    profiles.watch(alice["id"], old).set("film", 34_000, 120_000)
    assert profiles.watch("default", old).get("film")["position_ms"] == 12_000
    assert profiles.watch(bob["id"], old).get("film")["position_ms"] == 0
    restored = FamilyProfiles(tmp_path)
    assert restored.watch(alice["id"], old).get("film")["position_ms"] == 34_000


def test_manual_tmdb_choice_survives_forced_refresh_and_rename(tmp_path, monkeypatch):
    state, root, _ = make_state(tmp_path, monkeypatch)
    (root / "Movie.mkv").write_bytes(b"movie")
    state.library.scan()
    item = state.library.all()[0]
    state.cfgm.cfg.metadata.tmdb_api_key = "fake-test-key"
    monkeypatch.setattr(state.metadata, "_fetch_tmdb", lambda *_args, **_kwargs: MovieMetadata(title="Bonne correspondance", tmdb_id=123, matched=True))
    state.metadata.choose_match(item, 123, [item])
    monkeypatch.setattr(state.metadata, "_fetch_tmdb", lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("A manual choice must not be replaced")))
    changed = item.model_copy(update={"title": "Nouveau nom", "modified_at": 99})
    assert state.metadata.get(changed, force=True).title == "Bonne correspondance"


def test_profile_http_isolation_and_unknown_profile(tmp_path, monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from pluxy.api import routes_library, routes_profiles, routes_stream
    state, root, _ = make_state(tmp_path, monkeypatch)
    (root / "Film.mkv").write_bytes(b"movie")
    state.library.scan()
    item = state.library.all()[0]
    app = FastAPI()
    app.state.pluxy = state
    for router in (routes_library.router, routes_profiles.router, routes_stream.router):
        app.include_router(router)
    with TestClient(app) as client:
        profile = client.post("/api/profiles", json={"name": "Alice"}).json()["id"]
        headers = {"X-Pluxy-Profile": profile}
        assert client.post(f"/api/playback/progress/{item.id}", headers=headers, json={"position_ms": 60000, "duration_ms": 120000}).status_code == 200
        assert client.get(f"/api/playback/progress/{item.id}").json()["position_ms"] == 0
        assert client.get("/api/library/items/page", headers=headers).json()["items"][0]["in_progress"]
        assert not client.get("/api/library/items/page").json()["items"][0]["in_progress"]
        assert client.get("/api/library/items/page", headers={"X-Pluxy-Profile": "../invalid"}).status_code == 404
