import json
from pathlib import Path
import sys
import threading
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pluxy.catalogue import public_item
from pluxy.config import ConfigManager, PluxyConfig
from pluxy.library import Library
from pluxy.models import MediaInfo
from pluxy.profiles import registry
from pluxy.watched_organizer import WatchedOrganizer, rename_without_overwrite
from pluxy.watchstate import WatchStore


@pytest.fixture
def fixture(tmp_path, monkeypatch):
    root = tmp_path / 'SERIES'
    root.mkdir()
    data = tmp_path / 'data'
    data.mkdir()
    cfg = PluxyConfig()
    cfg.server.media_dirs = [str(root)]
    cfg.server.media_dir_types[str(root)] = 'series'
    default = tmp_path / 'default.json'
    default.write_text(cfg.model_dump_json(), encoding='utf-8')
    cfgm = ConfigManager(data, default)
    monkeypatch.setattr('pluxy.library.probe', lambda _, path: MediaInfo(path=path, container='mkv', duration=1800))
    state = SimpleNamespace(cfgm=cfgm, library=Library(cfgm, data), watch=WatchStore(data),
                            segments=SimpleNamespace(playback_active=lambda: False, pending=set()),
                            _decision_lock=threading.RLock(), _decisions={}, scan_async=lambda: True)
    state.organizer = WatchedOrganizer(state, data, start_worker=False)
    folder = root / 'Boreal' / 'Saison 1'
    folder.mkdir(parents=True)
    for index in (1, 2):
        (folder / f'Boreal.S01E{index:02}.mkv').write_bytes(b'video' + bytes([index]))
    (folder / 'Boreal.S01E01.fr.srt').write_text('Un sous-titre', encoding='utf-8')
    state.library.scan()
    yield state, folder, data
    state.organizer.close()


def enable(state, include_existing=True):
    state.organizer.configure(include_existing)
    state.cfgm.update({'server': {'organize_watched': True}})


def seen(state, item):
    state.watch.set(item.id, 1_650_000, 1_800_000)


def test_opt_in_and_existing_history_choice(fixture):
    state, folder, _ = fixture
    first, second = state.library.all()
    seen(state, first)
    assert not state.organizer.tick()
    assert not (folder / 'Vu').exists()
    enable(state, False)
    assert not state.organizer.tick()
    seen(state, second)
    assert state.organizer.tick()
    assert Path(first.path).exists()
    assert not Path(second.path).exists()
    state.organizer.configure(True)
    assert state.organizer.tick()
    assert not Path(first.path).exists()


def test_episode_identity_folders_subtitles_and_history_survive_moves_scans_and_restart(fixture):
    state, folder, data = fixture
    first, second = state.library.all()
    seen(state, first)
    before = public_item(first, None, state.watch.get(first.id)).model_dump()
    enable(state)
    assert state.organizer.tick()
    assert (folder / 'Vu' / Path(first.path).name).read_bytes() == b'video\x01'
    assert (folder / 'Vu' / 'Boreal.S01E01.fr.srt').read_text(encoding='utf-8') == 'Un sous-titre'
    assert Path(second.path).exists()
    for restart in (False, True, False):
        if restart:
            state.library = Library(state.cfgm, data)
        state.library.scan()
        current = state.library.get(first.id)
        assert len(state.library.all()) == 2
        assert current.path == str(folder / 'Vu' / Path(first.path).name)
        assert public_item(current, None, state.watch.get(first.id)).model_dump() == before
        assert state.watch.get(first.id)['watched']
        assert not state.organizer.tick()
    assert not (folder / 'Vu' / 'Vu').exists()


def test_no_overwrite_when_a_destination_already_exists(fixture):
    state, folder, _ = fixture
    item = state.library.all()[0]
    seen(state, item)
    enable(state)
    target = folder / 'Vu'
    target.mkdir()
    destination = target / Path(item.path).name
    destination.write_bytes(b'keep this different file')
    assert not state.organizer.tick()
    assert Path(item.path).read_bytes() == b'video\x01'
    assert destination.read_bytes() == b'keep this different file'
    assert not (target / 'Boreal.S01E01.fr.srt').exists()


@pytest.mark.parametrize('failure', ['rename', 'database'])
def test_partial_failure_rolls_back_media_and_subtitles_without_losing_index(fixture, monkeypatch, failure):
    state, folder, _ = fixture
    item = state.library.all()[0]
    seen(state, item)
    enable(state)
    def fail(*_):
        raise PermissionError('Locked for test')
    if failure == 'rename':
        def rename(source, target):
            if source.suffix == '.mkv':
                fail()
            rename_without_overwrite(source, target)
        monkeypatch.setattr('pluxy.watched_organizer.rename_without_overwrite', rename)
    else:
        monkeypatch.setattr(state.library.index, 'update', fail)
    assert not state.organizer.tick()
    assert Path(item.path).exists()
    assert (folder / 'Boreal.S01E01.fr.srt').exists()
    assert not (folder / 'Vu' / 'Boreal.S01E01.fr.srt').exists()
    assert state.library.get(item.id).path == item.path
    assert not list(state.organizer.directory.glob('*.json'))


def test_recovery_finishes_a_journal_after_interruption_before_index_update(fixture):
    state, folder, data = fixture
    item = state.library.all()[0]
    seen(state, item)
    enable(state)
    plan = state.organizer._plan(item)
    state.organizer.directory.mkdir()
    journal = state.organizer.directory / (item.id + '.json')
    journal.write_text(json.dumps(plan), encoding='utf-8')
    (folder / 'Vu').mkdir()
    rename_without_overwrite(Path(item.path), Path(plan['target']))
    state.library = Library(state.cfgm, data)
    assert state.organizer.recover()
    assert not journal.exists()
    state.library.scan()
    assert state.library.get(item.id).original_path == item.path
    assert state.library.get(item.id).series_key == item.series_key
    assert state.watch.get(item.id)['watched']


def test_multiple_profiles_suspend_the_option_and_all_new_moves(fixture):
    state, folder, _ = fixture
    seen(state, state.library.all()[0])
    enable(state)
    registry(state).create('Deuxième profil')
    assert state.organizer.status()['available'] is False
    assert state.organizer.status()['enabled'] is False
    assert not state.organizer.tick()
    assert not (folder / 'Vu').exists()


def test_aborted_plan_with_all_sources_present_does_not_block_library_scans(fixture):
    state, folder, _ = fixture
    item = state.library.all()[0]
    plan = state.organizer._plan(item)
    state.organizer.directory.mkdir()
    journal = state.organizer.directory / (item.id + '.json')
    journal.write_text(json.dumps(plan), encoding='utf-8')
    Path(item.path).write_bytes(b'a replacement source')
    assert state.organizer.recover()
    assert not journal.exists()
    assert list(state.organizer.directory.glob('*.cancelled-*'))
    assert Path(item.path).read_bytes() == b'a replacement source'
    assert not (folder / 'Vu').exists()


def test_duplicate_content_does_not_change_either_media_id_after_rescans(fixture):
    state, _, _ = fixture
    first, second = state.library.all()
    Path(second.path).write_bytes(Path(first.path).read_bytes())
    state.library.scan()
    ids = {item.id for item in state.library.all()}
    seen(state, state.library.get(first.id))
    enable(state)
    assert state.organizer.tick()
    state.library.scan()
    state.library.scan()
    assert {item.id for item in state.library.all()} == ids


def test_a_broken_organization_state_is_safe_and_does_not_prevent_startup(fixture):
    state, _, data = fixture
    state.organizer.path.write_text('{broken', encoding='utf-8')
    restored = WatchedOrganizer(state, data, start_worker=False)
    try:
        assert restored.error
        assert restored.moved == 0
        restored.configure(False)
        assert restored.error == ''
    finally:
        restored.close()


def test_a_vu_link_cannot_redirect_a_file_outside_its_directory(fixture, tmp_path):
    state, folder, _ = fixture
    destination = tmp_path / 'elsewhere'
    destination.mkdir()
    try:
        (folder / 'Vu').symlink_to(destination, target_is_directory=True)
    except OSError:
        pytest.skip('Creating a symbolic link is unavailable on this Windows installation')
    item = state.library.all()[0]
    seen(state, item)
    enable(state)
    assert not state.organizer.tick()
    assert Path(item.path).exists()
    assert not list(destination.iterdir())


@pytest.mark.parametrize('busy', ['scan', 'playback', 'analysis'])
def test_open_playback_scan_or_analysis_defers_moving_the_file(fixture, busy):
    state, folder, _ = fixture
    item = state.library.all()[0]
    seen(state, item)
    enable(state)
    if busy == 'scan':
        state.library._scanning = True
    elif busy == 'playback':
        state.segments.playback_active = lambda: True
    else:
        state.segments.pending.add(item.id)
    assert not state.organizer.tick()
    assert Path(item.path).exists()
    assert not (folder / 'Vu').exists()


def test_settings_require_explicit_existing_choice_and_one_local_profile(fixture, monkeypatch):
    from fastapi.testclient import TestClient
    from pluxy.main import app
    state, _, _ = fixture
    monkeypatch.setattr(app.state, 'pluxy', state, raising=False)
    local = TestClient(app, base_url='http://localhost', client=('127.0.0.1', 1234))
    remote = TestClient(app, base_url='http://192.168.1.10', client=('192.168.1.20', 1234))
    try:
        seen(state, state.library.all()[0])
        preview = local.get('/api/settings/watched-organization?preview=true').json()
        assert preview['available'] and preview['already_watched_count'] == 1
        patch = {'server': {'organize_watched': True}}
        assert local.patch('/api/settings', json=patch).status_code == 409
        assert not state.cfgm.cfg.server.organize_watched
        assert local.patch('/api/settings', json={'server': {'organize_watched': 'true'}}).status_code == 422
        patch['watched_organization'] = {'include_existing': False}
        assert local.patch('/api/settings', json=patch).status_code == 200
        assert state.cfgm.cfg.server.organize_watched
        assert not state.organizer.tick()
        assert remote.get('/api/settings/watched-organization').status_code == 403
        assert remote.patch('/api/settings', json=patch).status_code == 403
        registry(state).create('Deuxième profil')
        assert not local.get('/api/settings/watched-organization').json()['available']
        assert local.patch('/api/settings', json=patch).status_code == 422
    finally:
        local.close()
        remote.close()
