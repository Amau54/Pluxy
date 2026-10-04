"""Validate startup recovery and the tray restart lifecycle without opening a UI."""
import importlib.machinery
import importlib.util
import json
import sys
from pathlib import Path
from unittest.mock import Mock

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))


def load_tray(monkeypatch, tmp_path):
    monkeypatch.setenv("PLUXY_DATA_DIR", str(tmp_path))
    loader = importlib.machinery.SourceFileLoader("pluxy_tray_test", str(BASE / "pluxy_tray.pyw"))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def test_launcher_recovers_corrupt_config(monkeypatch, tmp_path):
    from run import _read_host_port
    monkeypatch.setenv("PLUXY_DATA_DIR", str(tmp_path))
    data = tmp_path / "Pluxy"
    data.mkdir()
    (data / "config.json").write_text("{broken", encoding="utf-8")
    assert _read_host_port() == ("0.0.0.0", 8420)
    assert list(data.glob("config.invalid-*.json"))
    assert json.loads((data / "config.json").read_text())["server"]["port"] == 8420


def test_tray_restart_reads_changed_port(monkeypatch, tmp_path):
    tray = load_tray(monkeypatch, tmp_path)
    monkeypatch.setattr(tray, "_read_port", lambda: 65535)
    monkeypatch.setattr(tray, "_server_up", lambda: False)
    popen = Mock(return_value=Mock())
    monkeypatch.setattr(tray.subprocess, "Popen", popen)
    tray.start_server()
    assert tray.PORT == 65535
    assert tray.URL == "http://localhost:65535"
    popen.assert_called_once()


def test_tray_recognizes_only_pluxy_health(monkeypatch, tmp_path):
    tray = load_tray(monkeypatch, tmp_path)
    response = Mock()
    response.__enter__ = Mock(return_value=response)
    response.__exit__ = Mock(return_value=False)
    monkeypatch.setattr(tray, "urlopen", Mock(return_value=response))
    response.read.return_value = b'{"status":"ok","app":"other"}'
    assert not tray._server_up()
    response.read.return_value = b'{"status":"ok","app":"pluxy"}'
    assert tray._server_up()


def test_tray_lock_survives_port_changes(monkeypatch, tmp_path):
    first = load_tray(monkeypatch, tmp_path)
    second = load_tray(monkeypatch, tmp_path)
    first.PORT = 65535
    second.PORT = 8420
    try:
        assert first._acquire_single_instance()
        assert not second._acquire_single_instance()
        assert second._existing_instance_port() == 65535
    finally:
        if first._lock_file:
            first._lock_file.close()
    assert second._acquire_single_instance()
    second._lock_file.close()


def test_second_tray_opens_active_port_without_starting_server(monkeypatch, tmp_path):
    tray = load_tray(monkeypatch, tmp_path)
    monkeypatch.setattr(tray, "_acquire_single_instance", lambda: False)
    monkeypatch.setattr(tray, "_existing_instance_port", lambda: 18420)
    open_ui = Mock()
    monkeypatch.setattr(tray, "open_ui", open_ui)
    tray.PORT = 18421  # A newly saved port has not yet been applied by the owner.
    tray.main()
    assert tray.URL == "http://localhost:18420"
    open_ui.assert_called_once_with(start_if_needed=False)
