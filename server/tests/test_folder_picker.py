"""Sélection native, annulation, nettoyage et accès limité au PC serveur."""
import ctypes
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pluxy import folder_picker as picker
from pluxy.api import routes_settings


@pytest.fixture
def native(monkeypatch):
    state = SimpleNamespace(show_result=0, failure=None, releases=[], options=0, path=r"C:\Médias\Été 🎬")
    state.buffer = ctypes.create_unicode_buffer(state.path)

    def create(*args):
        ctypes.cast(args[-1], ctypes.POINTER(ctypes.c_void_p)).contents.value = 1
        return 0

    state.ole = SimpleNamespace(
        CoInitializeEx=Mock(return_value=0), CoCreateInstance=Mock(side_effect=create),
        CoUninitialize=Mock(), CoTaskMemFree=Mock(),
    )
    user = SimpleNamespace(GetForegroundWindow=Mock(return_value=None))
    monkeypatch.setattr(ctypes, "WinDLL", lambda name: state.ole if name == "ole32" else user, raising=False)

    def method(pointer, index, *_):
        def call(*args):
            if index == state.failure:
                return 0x80004005
            if index == 2:
                state.releases.append(pointer.value)
            elif index == 3:
                return state.show_result
            elif index == 10:
                ctypes.cast(args[-1], ctypes.POINTER(ctypes.c_uint32)).contents.value = 0x1000
            elif index == 9:
                state.options = args[-1]
            elif index == 20:
                ctypes.cast(args[-1], ctypes.POINTER(ctypes.c_void_p)).contents.value = 2
            elif index == 5:
                ctypes.cast(args[-1], ctypes.POINTER(ctypes.c_void_p)).contents.value = ctypes.addressof(state.buffer)
            return 0
        return call

    monkeypatch.setattr(picker, "_method", method)
    monkeypatch.setattr(picker.sys, "platform", "win32")
    return state


def test_native_selection_preserves_unicode_and_releases_resources(native, monkeypatch):
    monkeypatch.setitem(sys.modules, "tkinter", None)
    assert picker.choose_media_folder() == native.path
    assert native.options & picker._FOLDER_OPTIONS == picker._FOLDER_OPTIONS
    assert native.options & 0x1000  # Préserve les options du Shell.
    assert native.releases == [2, 1]
    native.ole.CoTaskMemFree.assert_called_once()
    native.ole.CoUninitialize.assert_called_once()


def test_cancel_is_an_empty_selection_without_reading_a_result(native):
    native.show_result = picker._CANCELLED
    assert picker.choose_media_folder() == ""
    assert native.releases == [1]
    native.ole.CoTaskMemFree.assert_not_called()
    native.ole.CoUninitialize.assert_called_once()


@pytest.mark.parametrize("failure,releases", [(9, [1]), (17, [1]), (3, [1]), (20, [1]), (5, [2, 1])])
def test_native_failures_release_com_and_allow_another_attempt(native, failure, releases):
    native.failure = failure
    with pytest.raises(OSError):
        picker.choose_media_folder()
    assert native.releases == releases
    native.ole.CoUninitialize.assert_called_once()
    native.failure = None
    assert picker.choose_media_folder() == native.path


def test_com_initialization_failure_does_not_uninitialize_another_apartment(native):
    native.ole.CoInitializeEx.return_value = 0x80010106
    with pytest.raises(OSError):
        picker.choose_media_folder()
    native.ole.CoUninitialize.assert_not_called()
    native.ole.CoCreateInstance.assert_not_called()
    native.ole.CoInitializeEx.return_value = 0
    assert picker.choose_media_folder() == native.path


def test_a_second_request_cannot_open_another_dialog(monkeypatch):
    choose = Mock()
    monkeypatch.setattr(picker, "_choose_folder", choose)
    with picker._picker_lock:
        with pytest.raises(picker.FolderPickerBusy):
            picker.choose_media_folder()
    choose.assert_not_called()


@pytest.mark.parametrize("path", ["", r"C:\Films", r"\\NAS\Vidéo été"])
def test_api_returns_selection_or_cancellation(monkeypatch, path):
    monkeypatch.setattr(routes_settings, "choose_media_folder", lambda: path)
    app = FastAPI()
    app.include_router(routes_settings.router)
    with TestClient(app, base_url="http://localhost", client=("127.0.0.1", 1234)) as client:
        response = client.post("/api/settings/pick-folder")
    assert response.status_code == 200
    assert response.json() == {"path": path}


def test_remote_requests_never_open_the_selector(monkeypatch):
    choose = Mock()
    monkeypatch.setattr(routes_settings, "choose_media_folder", choose)
    app = FastAPI()
    app.include_router(routes_settings.router)
    with TestClient(app, base_url="http://localhost", client=("192.168.1.42", 1234)) as client:
        assert client.post("/api/settings/pick-folder").status_code == 403
    choose.assert_not_called()


@pytest.mark.parametrize("error,code", [(picker.FolderPickerBusy("Déjà ouvert"), 409), (OSError("COM indisponible"), 503)])
def test_api_failure_response(monkeypatch, error, code):
    monkeypatch.setattr(routes_settings, "choose_media_folder", Mock(side_effect=error))
    app = FastAPI()
    app.include_router(routes_settings.router)
    with TestClient(app, base_url="http://localhost", client=("127.0.0.1", 1234)) as client:
        response = client.post("/api/settings/pick-folder")
    assert response.status_code == code


@pytest.mark.skipif(sys.platform != "win32", reason="Shell Windows")
def test_real_native_dialog_can_be_configured_on_a_reused_api_worker():
    def configure():
        with picker._WindowsFolderDialog() as dialog:
            options = ctypes.c_uint32()
            picker._check(picker._method(dialog.dialog, 10, ctypes.c_long, ctypes.POINTER(ctypes.c_uint32))(dialog.dialog, ctypes.byref(options)))
            return options.value

    # Aucun affichage ni sélection automatique : teste le vrai COM et le mode dossier.
    with ThreadPoolExecutor(max_workers=1) as pool:
        for _ in range(2):
            assert pool.submit(configure).result() & picker._FOLDER_OPTIONS == picker._FOLDER_OPTIONS
