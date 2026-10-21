import json

import pytest

from tools import publish_local as local


@pytest.fixture
def windows(tmp_path):
    setup = tmp_path / "Pluxy-Setup.exe"
    setup.write_bytes(b"validated installer")
    info = {"schema": 1, "dirty": False, "commit": "a" * 40, "version": "1.8.33",
            "sha256": local.apk.digest(setup), "size": setup.stat().st_size}
    (tmp_path / "PC_BUILD_INFO.json").write_text(json.dumps(info))
    (tmp_path / "PC_SHA256SUMS.txt").write_text(info["sha256"] + "  Pluxy-Setup.exe\n")
    return tmp_path


@pytest.mark.parametrize("field,value", [("dirty", True), ("commit", "b" * 40),
                                       ("version", "1.8.32"), ("size", 0)])
def test_windows_rejects_wrong_provenance_before_network(windows, field, value):
    manifest = windows / "PC_BUILD_INFO.json"
    info = json.loads(manifest.read_text())
    info[field] = value
    manifest.write_text(json.dumps(info))
    with pytest.raises(ValueError, match="provenance"):
        local.publish_windows(None, windows, "a" * 40, "1.8.33", 123)


def test_windows_detects_modified_installer(windows):
    (windows / "Pluxy-Setup.exe").write_bytes(b"tampered")
    with pytest.raises(ValueError, match="provenance"):
        local.validate_windows(windows, "a" * 40, "1.8.33")


def test_windows_publishes_only_the_three_public_assets(windows):
    (windows / "private-log.txt").write_text("not for publication")
    files = local.validate_windows(windows, "a" * 40, "1.8.33")
    assert {p.name for p in files} == {"Pluxy-Setup.exe", "PC_BUILD_INFO.json", "PC_SHA256SUMS.txt"}


def test_failed_local_tests_never_push_or_publish(monkeypatch):
    monkeypatch.setattr(local, "clean_commit", lambda: "a" * 40)
    monkeypatch.setattr(local.apk, "run", lambda *args: "https://github.com/Amau54/Pluxy.git")
    monkeypatch.setattr(local.Path, "mkdir", lambda *args, **kwargs: None)
    def fail(*args):
        raise RuntimeError("unit tests failed")
    monkeypatch.setattr(local, "build_and_test", fail)
    monkeypatch.setattr(local.apk, "Publisher", lambda *args: pytest.fail("publication after failed tests"))
    calls = []
    monkeypatch.setattr(local, "execute", lambda *args: calls.append(args))
    monkeypatch.setattr(local.sys, "argv", ["publish_local.py", "--publish"])
    with pytest.raises(RuntimeError, match="unit tests failed"):
        local.main()
    assert not any(call[:2] == ("git", "push") for call in calls)


def test_all_actions_require_manual_dispatch():
    workflows = local.ROOT / ".github/workflows"
    import re
    for path in workflows.glob("*.yml"):
        source = path.read_text(encoding="utf-8")
        triggers = source.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        # Stop at the next top-level section (concurrency can precede permissions).
        triggers = re.split(r"\n[^ \n]", triggers, maxsplit=1)[0]
        events = re.findall(r"^  ([a-z_]+):", triggers, re.M)
        assert events == ["workflow_dispatch"], path.name
