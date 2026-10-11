from types import SimpleNamespace
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from pluxy.catalogue import Catalogue, Query
from pluxy.models import MediaItem
from pluxy.api.routes_library import router


def catalogue(items):
    library = SimpleNamespace(snapshot=lambda: (1, items))
    metadata = SimpleNamespace(snapshot=lambda ids: (1, {}))
    watch = SimpleNamespace(snapshot=lambda: (1, {}))
    return Catalogue(library, metadata, watch)


def media(id, **values):
    return MediaItem(id=id, title=id, path="/private/file", container="mkv", size=1, duration=60, **values)


def test_filtered_series_card_opens_all_seasons_without_filters():
    values = [media(f"ep-{season}", kind="series", series_key="boreal", series_name="Boreal", season=season, episode=1)
              for season in range(1, 9)] + [media("other", kind="series", series_key="other")]
    index = catalogue(values)
    cards, total, _ = index.page(Query(kind="series", q="ep-3", grouped=True), 0, 500)
    assert total == 1 and cards[0].group_count == 1
    episodes, total, _ = index.page(Query(kind="series", group_id=cards[0].id), 0, 500)
    assert total == 8 and {episode.season for episode in episodes} == set(range(1, 9))
    assert all(episode.path == "" for episode in episodes)


def test_photo_groups_keep_sources_and_folders_separate():
    index = catalogue([media("a", kind="photo", source_id="one", relative_path="Trip/a.jpg"),
                       media("b", kind="photo", source_id="one", relative_path="Trip/b.jpg"),
                       media("c", kind="photo", source_id="two", relative_path="Trip/c.jpg"),
                       media("d", kind="photo", source_id="one", relative_path="Trip/Child/d.jpg")])
    cards, total, _ = index.page(Query(kind="photo", grouped=True), 0, 500)
    assert total == 3 and sorted(card.group_count for card in cards) == [1, 1, 2]
    photos, total, _ = index.page(Query(kind="photo", group_id="a"), 0, 500)
    assert total == 2 and {photo.id for photo in photos} == {"a", "b"}


def test_large_catalogue_http_page_is_accessible_after_ten_thousand():
    index = catalogue([media(f"clip-{n:05}", kind="clip") for n in range(11_001)])
    app = FastAPI()
    app.include_router(router)
    app.state.pluxy = SimpleNamespace(catalogue=index)
    with TestClient(app) as client:
        first = client.get("/api/library/items/page", params={"kind": "clip", "offset": 10_500}).json()
        assert len(first["items"]) == 500 and first["next_offset"] == 11_000
        last = client.get("/api/library/items/page", params={"kind": "clip", "offset": 11_000, "revision": first["revision"]})
        assert last.status_code == 200 and len(last.json()["items"]) == 1
        assert last.json()["next_offset"] is None


def test_album_groups_preserve_artist_and_revision_conflicts():
    index = catalogue([media("a", kind="audio", artist="Alice", album="Live"),
                       media("b", kind="audio", artist="Alice", album="Live"),
                       media("c", kind="audio", artist="Bob", album="Live")])
    cards, total, _ = index.page(Query(kind="audio", grouped=True), 0, 500)
    assert total == 2 and sorted(card.group_count for card in cards) == [1, 2]
    with pytest.raises(ValueError):
        index.page(Query(kind="audio", grouped=True), 1, 500, "old")


def test_audio_folder_groups_keep_sources_and_subfolders_separate():
    values = [media("a", kind="audio", artist="Alice", album="Live"),
              media("b", kind="audio", artist="Bob", album="Other"),
              media("c", kind="audio", artist="Alice", album="Live")]
    values = [item.model_copy(update={"source_id": "disk1" if i < 2 else "disk2",
              "relative_path": "Concert/" + item.id + ".mp3"}) for i, item in enumerate(values)]
    index = catalogue(values)
    cards, total, _ = index.page(Query(kind="audio", grouped=True, audio_folders=True), 0, 500)
    assert total == 2 and sorted(card.group_count for card in cards) == [1, 2]
    tracks, _, _ = index.page(Query(kind="audio", group_id="a", audio_folders=True), 0, 500)
    assert {track.id for track in tracks} == {"a", "b"}
