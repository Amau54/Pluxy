from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from pluxy import autostart


def test_installed_desktop_owns_autostart_without_a_console(tmp_path, monkeypatch):
    module = tmp_path / "server/pluxy/autostart.py"
    module.parent.mkdir(parents=True)
    module.touch()
    desktop = tmp_path / "Pluxy.exe"
    desktop.touch()
    monkeypatch.setattr(autostart, "__file__", str(module))
    assert autostart._launch_command() == f'"{desktop}" --server --background'


def test_source_checkout_keeps_its_existing_tray_launcher(tmp_path, monkeypatch):
    module = tmp_path / "server/pluxy/autostart.py"
    module.parent.mkdir(parents=True)
    module.touch()
    tray = module.parent.parent / "pluxy_tray.pyw"
    tray.touch()
    monkeypatch.setattr(autostart, "__file__", str(module))
    assert autostart._launch_command().endswith(f'"{tray}"')
