from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest

from tools import publish_apk as pub

SHA = "a" * 40
CERT = "b" * 64
OLD_SHA = "c" * 40
OLD_CERT = "d" * 64


def apk_info(path: Path, _tools: Path, require_release: bool = False) -> dict:
    return {"name": path.name, "package": "com.pluxy.server" if "Server" in path.name else "com.pluxy.tv",
            "version_name": "1.5.1", "version_code": 46, "min_sdk": 23,
            "sha256": pub.digest(path), "signing_cert_sha256": [CERT]}


@pytest.fixture
def distribution(tmp_path, monkeypatch):
    monkeypatch.setattr(pub, "inspect_apk", apk_info)
    for name in ("Pluxy-debug.apk", "Pluxy-Server-Android-debug.apk"):
        (tmp_path / name).write_bytes(name.encode())
    pub.manifest(tmp_path, Path("sdk"), "debug", SHA, "https://github.com/owner/repo/actions/runs/1")
    return tmp_path


def test_manifest_uses_verified_identity_and_covers_its_own_provenance(distribution):
    info = json.loads((distribution / "BUILD_INFO.json").read_text())
    assert info["schema"] == 1 and info["commit"] == SHA
    assert {a["package"] for a in info["artifacts"]} == set(pub.PACKAGES)
    assert all(a["version_code"] == 46 and a["min_sdk"] == 23 for a in info["artifacts"])
    assert f"{pub.digest(distribution / 'BUILD_INFO.json')}  BUILD_INFO.json" in (
        distribution / "SHA256SUMS.txt").read_text()


def test_manifest_refuses_wrong_keystore_certificate(distribution, monkeypatch):
    monkeypatch.setenv("PLAYER_CERT_SHA256", "c" * 64)
    with pytest.raises(ValueError, match="supplied keystore"):
        pub.manifest(distribution, Path("sdk"), "debug", SHA, None)


def test_unified_apk_can_replace_legacy_two_apk_distribution(distribution):
    (distribution / "Pluxy-Server-Android-debug.apk").unlink()
    info = pub.manifest(distribution, Path("sdk"), "debug", SHA, None)
    assert len(info["artifacts"]) == 1
    assert info["artifacts"][0]["package"] == "com.pluxy.tv"
    assert "Pluxy-Server-Android" not in (distribution / "SHA256SUMS.txt").read_text()


def test_unified_distribution_requires_reader_and_rejects_duplicate_packages():
    assert not pub.valid_apk_packages([{"name": "server.apk", "package": "com.pluxy.server"}])
    assert not pub.valid_apk_packages([
        {"name": "one.apk", "package": "com.pluxy.tv"},
        {"name": "two.apk", "package": "com.pluxy.tv"},
    ])


def test_apk_parser_requires_valid_signature_and_known_package(tmp_path, monkeypatch):
    path = tmp_path / "player.apk"
    path.write_bytes(b"apk")
    monkeypatch.setattr(pub, "run", lambda *args: (
        "package: name='com.pluxy.tv' versionCode='46' versionName='1.5.1'\nsdkVersion:'23'\n"
        if "badging" in args else f"Signer #1 certificate SHA-256 digest: {CERT}\n"))
    assert pub.inspect_apk(path, tmp_path)["signing_cert_sha256"] == [CERT]
    monkeypatch.setattr(pub, "run", lambda *args: "invalid")
    with pytest.raises(ValueError, match="identity or signature"):
        pub.inspect_apk(path, tmp_path)


def test_debuggable_apk_cannot_be_official_release(tmp_path, monkeypatch):
    path = tmp_path / "player.apk"
    path.write_bytes(b"apk")
    monkeypatch.setattr(pub, "run", lambda *args: (
        "package: name='com.pluxy.tv' versionCode='46' versionName='1.5.1'\nsdkVersion:'23'\napplication-debuggable\n"
        if "badging" in args else f"Signer #1 certificate SHA-256 digest: {CERT}\n"))
    with pytest.raises(ValueError, match="Debuggable APK"):
        pub.inspect_apk(path, tmp_path, require_release=True)


@pytest.mark.parametrize("status,missing", [(404, True), (403, False), (500, False)])
def test_only_http_404_means_release_absent(monkeypatch, status, missing):
    monkeypatch.setattr(pub.subprocess, "run", lambda *args, **kwargs:
                        subprocess.CompletedProcess(args, 1, b"", f"gh: error (HTTP {status})".encode()))
    publisher = pub.Publisher("owner/repo")
    if missing:
        assert publisher.release("dev-latest") is None
    else:
        with pytest.raises(RuntimeError, match=str(status)):
            publisher.release("dev-latest")


class RecordingPublisher(pub.Publisher):
    def __init__(self):
        super().__init__("owner/repo")
        self.events = []
        self.current = {"id": 2, "tag_name": "dev-latest"}
        self.upload_failure = False

    def api(self, endpoint, method="GET", payload=None, missing_ok=False):
        self.events.append((endpoint, method, payload))
        if endpoint == "git/ref/heads/main":
            return {"object": {"sha": SHA}}
        if endpoint == "releases/tags/dev-latest":
            return self.current
        if endpoint.startswith("releases/tags/"):
            return None
        if endpoint == "releases" and method == "POST":
            return {"id": 1, "html_url": "https://github.com/owner/repo/releases/tag/candidate", **payload}
        if endpoint == "releases/1" and method == "PATCH":
            return {"id": 1, "html_url": "https://github.com/owner/repo/releases/tag/candidate", **payload}
        if endpoint == "git/ref/tags/dev-latest":
            return {"object": {"sha": "c" * 40, "type": "commit"}}
        if endpoint.startswith("git/ref/tags/"):
            return None
        return {"id": 2, **(payload or {})}

    def upload(self, tag, release_id, files, clobber=False):
        self.events.append(("verified-upload", tag, [p.name for p in files]))
        if self.upload_failure:
            raise ValueError("Incomplete remote asset")

    def check_debug_compatibility(self, current, info, build_tools):
        self.events.append(("compatibility", current, info["commit"]))


def test_debug_checks_compatibility_then_publishes_complete_candidate_before_alias(distribution):
    p = RecordingPublisher()
    p.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes")
    events = p.events
    compatibility = next(i for i, e in enumerate(events) if e[0] == "compatibility")
    candidate_upload = next(i for i, e in enumerate(events) if e[:2] == ("verified-upload", "dev-aaaaaaaaaaaa-1-1"))
    candidate_publish = next(i for i, e in enumerate(events) if e[:2] == ("releases/1", "PATCH"))
    alias_hide = next(i for i, e in enumerate(events) if e[:2] == ("releases/2", "PATCH") and e[2] == {"draft": True})
    alias_upload = next(i for i, e in enumerate(events) if e[:2] == ("verified-upload", "dev-latest"))
    alias_tag = next(i for i, e in enumerate(events) if e[:2] == ("git/refs/tags/dev-latest", "PATCH"))
    alias_publish = len(events) - 1
    assert compatibility < candidate_upload < candidate_publish < alias_hide < alias_upload < alias_tag < alias_publish
    assert events[-1][2]["draft"] is False
    assert SHA in events[-1][2]["body"]


def test_failed_candidate_upload_remains_draft_and_does_not_touch_alias(distribution):
    p = RecordingPublisher()
    p.upload_failure = True
    with pytest.raises(ValueError, match="Incomplete"):
        p.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes")
    assert not any(e[0] == "releases/2" or (e[1] == "PATCH" and e[2].get("draft") is False)
                   for e in p.events if isinstance(e[2], dict))


def test_changed_apk_is_rejected_before_any_github_operation(distribution):
    p = RecordingPublisher()
    (distribution / "Pluxy-debug.apk").write_bytes(b"changed")
    with pytest.raises(ValueError, match="differs from its manifest"):
        p.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes")
    assert not p.events


def test_failed_alias_upload_keeps_candidate_published_and_alias_hidden(distribution):
    p = RecordingPublisher()
    original_upload = p.upload

    def upload(tag, release_id, files, clobber=False):
        original_upload(tag, release_id, files, clobber)
        if tag == "dev-latest":
            raise ValueError("alias transfer failed")

    p.upload = upload
    with pytest.raises(ValueError, match="alias transfer"):
        p.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes")
    assert ("releases/1", "PATCH", {"draft": False}) in p.events
    assert ("releases/2", "PATCH", {"draft": True}) in p.events
    assert not any(e[0] == "git/refs/tags/dev-latest" or
                   (e[0] == "releases/2" and isinstance(e[2], dict) and e[2].get("draft") is False)
                   for e in p.events)


def test_source_tag_mismatch_refused_before_github_operation(distribution):
    p = RecordingPublisher()
    with pytest.raises(ValueError, match="source commit differ"):
        p.publish(distribution, "dev-cccccccccccc-1-1", SHA, Path("sdk"), True, "notes")
    assert not p.events


@pytest.mark.parametrize("previous_cert,previous_code", [("c" * 64, 45), (CERT, 47)])
def test_existing_channel_refuses_incompatible_signer_or_downgrade(distribution, monkeypatch, previous_cert, previous_code):
    info = json.loads((distribution / "BUILD_INFO.json").read_text())

    def download(*args, **kwargs):
        (Path(args[-1]) / args[args.index("--pattern") + 1]).write_bytes(b"old")
        return ""

    monkeypatch.setattr(pub, "run", download)
    monkeypatch.setattr(pub, "inspect_apk", lambda path, tools:
                        {**apk_info(path, tools), "version_code": previous_code, "signing_cert_sha256": [previous_cert]})
    with pytest.raises(ValueError, match="incompatible|downgrade"):
        pub.Publisher("owner/repo").check_debug_compatibility({"id": 2}, info, Path("sdk"))


def test_remote_digest_mismatch_is_not_published(tmp_path, monkeypatch):
    path = tmp_path / "APK.apk"
    path.write_bytes(b"apk")
    publisher = pub.Publisher("owner/repo")
    monkeypatch.setattr(publisher, "api", lambda *args: {"assets": [{"name": path.name,
                        "size": 3, "state": "uploaded", "digest": "sha256:" + "c" * 64}]})
    with pytest.raises(ValueError, match="Remote checksum"):
        publisher.verify_assets(1, [path])


@pytest.mark.parametrize("annotated", [False, True])
def test_old_lightweight_or_annotated_tag_refused_before_creating_release(monkeypatch, annotated):
    publisher = pub.Publisher("owner/repo")
    events = []

    def api(endpoint, method="GET", payload=None, missing_ok=False):
        events.append((endpoint, method))
        if endpoint.startswith("releases/tags/"):
            return None
        if endpoint.startswith("git/ref/tags/"):
            return {"object": {"sha": "d" * 40 if annotated else "c" * 40,
                               "type": "tag" if annotated else "commit"}}
        if endpoint == "git/tags/" + "d" * 40:
            return {"object": {"sha": "c" * 40, "type": "commit"}}
        raise AssertionError(endpoint)

    monkeypatch.setattr(publisher, "api", api)
    with pytest.raises(ValueError, match="different source commit"):
        publisher.create_complete("v1.5.1", SHA, "title", "notes", [], False)
    assert all(method == "GET" for _, method in events)


@pytest.mark.parametrize("annotated", [False, True])
def test_current_tag_allowed_for_tag_trigger_and_manual_publication(monkeypatch, annotated):
    publisher = pub.Publisher("owner/repo")

    def api(endpoint, method="GET", payload=None, missing_ok=False):
        if endpoint.startswith("releases/tags/"):
            return None
        if endpoint.startswith("git/ref/tags/"):
            return {"object": {"sha": "d" * 40 if annotated else SHA,
                               "type": "tag" if annotated else "commit"}}
        if endpoint == "git/tags/" + "d" * 40:
            return {"object": {"sha": SHA, "type": "commit"}}
        raise AssertionError(endpoint)

    monkeypatch.setattr(publisher, "api", api)
    publisher.require_unused_tag("v1.5.1", SHA)


def test_absent_tag_allowed_for_manual_publication(monkeypatch):
    publisher = pub.Publisher("owner/repo")
    monkeypatch.setattr(publisher, "api", lambda *args, **kwargs: None)
    publisher.require_unused_tag("v1.5.1", SHA)


def test_tag_changed_during_upload_is_not_published(monkeypatch):
    publisher = pub.Publisher("owner/repo")
    events = []
    tag_reads = 0

    def api(endpoint, method="GET", payload=None, missing_ok=False):
        nonlocal tag_reads
        events.append((endpoint, method, payload))
        if endpoint.startswith("releases/tags/"):
            return None
        if endpoint == "git/ref/tags/v1.5.1":
            tag_reads += 1
            return {"object": {"sha": SHA if tag_reads == 1 else "c" * 40, "type": "commit"}}
        if endpoint == "git/ref/heads/main":
            return {"object": {"sha": SHA}}
        if endpoint == "releases" and method == "POST":
            return {"id": 1}
        raise AssertionError(endpoint)

    monkeypatch.setattr(publisher, "api", api)
    monkeypatch.setattr(publisher, "upload", lambda *args, **kwargs: None)
    with pytest.raises(ValueError, match="different source commit"):
        publisher.create_complete("v1.5.1", SHA, "title", "notes", [], False)
    assert not any(method == "PATCH" for _, method, _ in events)


def transition_value():
    return {"schema": 1, "repository": "owner/repo", "from_release_id": 2,
            "from_commit": OLD_SHA, "to_commit": SHA,
            "packages": {package: {
                "from": {"version_name": "1.4.5", "version_code": 44,
                         "signing_cert_sha256": [OLD_CERT]},
                "to": {"version_name": "1.5.1", "version_code": 46,
                       "signing_cert_sha256": [CERT]}} for package in pub.PACKAGES}}


class TransitionPublisher(RecordingPublisher):
    def __init__(self, files):
        super().__init__()
        self.current.update({"draft": False, "html_url": "https://github.com/owner/repo/releases/tag/dev-latest",
                             "assets": [{"id": 100 + index, "name": name, "state": "uploaded",
                                         "size": len(data), "digest": "sha256:" + pub.hashlib.sha256(data).hexdigest()}
                                        for index, (name, data) in enumerate(files.items())]})
        self.archive = None
        self.archive_failure = False
        self.changed_source = False

    def api(self, endpoint, method="GET", payload=None, missing_ok=False):
        if endpoint == "git/ref/tags/dev-latest":
            self.events.append((endpoint, method, payload))
            return {"object": {"sha": OLD_SHA, "type": "commit"}}
        if endpoint == "releases/tags/dev-latest" and self.changed_source:
            self.events.append((endpoint, method, payload))
            return {**self.current, "assets": []}
        if endpoint.startswith("releases/tags/dev-legacy-"):
            self.events.append((endpoint, method, payload))
            return self.archive
        if endpoint == "releases" and method == "POST" and payload["tag_name"].startswith("dev-legacy-"):
            self.events.append((endpoint, method, payload))
            self.archive = {"id": 3, "html_url": "https://github.com/owner/repo/releases/tag/legacy", **payload}
            return self.archive
        if endpoint.startswith("git/ref/tags/dev-legacy-"):
            self.events.append((endpoint, method, payload))
            return {"object": {"sha": OLD_SHA, "type": "commit"}} if self.archive else None
        if endpoint == "releases/3":
            self.events.append((endpoint, method, payload))
            if payload:
                self.archive.update(payload)
            return self.archive
        return super().api(endpoint, method, payload, missing_ok)

    def upload(self, tag, release_id, files, clobber=False):
        super().upload(tag, release_id, files, clobber)
        if tag.startswith("dev-legacy-"):
            if self.archive_failure:
                raise ValueError("legacy upload incomplete")
            self.archive["assets"] = [{"id": 200 + index, "name": path.name, "state": "uploaded",
                                       "size": path.stat().st_size, "digest": "sha256:" + pub.digest(path)}
                                      for index, path in enumerate(files)]


@pytest.fixture
def transition_setup(distribution, monkeypatch):
    files = {name: b"old-" + name.encode() for name in
             ("Pluxy-debug.apk", "Pluxy-Server-Android-debug.apk", "SHA256SUMS.txt")}
    publisher = TransitionPublisher(files)

    def download(*args, binary=False):
        assert binary and args[0:2] == ("gh", "api")
        asset_id = int(args[2].split("/")[-1])
        return files[publisher.current["assets"][asset_id - 100]["name"]]

    def inspect(path, tools, require_release=False):
        info = apk_info(path, tools, require_release)
        if path.parent.name.startswith("pluxy-signing-archive-"):
            return {**info, "version_name": "1.4.5", "version_code": 44,
                    "signing_cert_sha256": [OLD_CERT]}
        return info

    monkeypatch.setattr(pub, "run", download)
    monkeypatch.setattr(pub, "inspect_apk", inspect)
    return publisher, transition_value()


def test_transition_archives_all_original_assets_before_alias_mutation_and_warns(distribution, transition_setup):
    publisher, transition = transition_setup
    publisher.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes", transition)
    events = publisher.events
    archive_upload = next(i for i, e in enumerate(events) if e[:2] ==
                          ("verified-upload", "dev-legacy-cccccccccccc-2"))
    archive_publish = next(i for i, e in enumerate(events) if e[:2] == ("releases/3", "PATCH"))
    candidate_upload = next(i for i, e in enumerate(events) if e[:2] ==
                            ("verified-upload", "dev-aaaaaaaaaaaa-1-1"))
    alias_hide = next(i for i, e in enumerate(events) if e[:2] == ("releases/2", "PATCH"))
    assert archive_upload < archive_publish < candidate_upload < alias_hide
    assert set(events[archive_upload][2]) == {a["name"] for a in publisher.current["assets"]}
    candidate = next(e for e in events if e[0] == "releases" and e[1] == "POST" and
                     e[2]["tag_name"] == "dev-aaaaaaaaaaaa-1-1")
    assert pub.REINSTALL_NOTICE in candidate[2]["body"]
    assert pub.REINSTALL_NOTICE in events[-1][2]["body"]
    assert "https://github.com/owner/repo/releases/tag/legacy" in events[-1][2]["body"]
    info = json.loads((distribution / "BUILD_INFO.json").read_text())
    assert info["signing_transition"]["packages"] == transition["packages"]
    assert f"{pub.digest(distribution / 'BUILD_INFO.json')}  BUILD_INFO.json" in (
        distribution / "SHA256SUMS.txt").read_text()


@pytest.mark.parametrize("field,value", [("repository", "other/repo"), ("to_commit", OLD_SHA),
                                        ("from_release_id", 0), ("packages", {})])
def test_transition_wrong_scope_refused_before_github(distribution, transition_setup, field, value):
    publisher, transition = transition_setup
    transition[field] = value
    with pytest.raises(ValueError, match="Signing transition"):
        publisher.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes", transition)
    assert not publisher.events


@pytest.mark.parametrize("side,field,value", [("from", "signing_cert_sha256", ["e" * 64]),
                                            ("from", "version_code", 45),
                                            ("from", "version_name", "1.4.4"),
                                            ("to", "signing_cert_sha256", ["e" * 64])])
def test_transition_requires_exact_old_and_new_identities(distribution, transition_setup, side, field, value):
    publisher, transition = transition_setup
    transition["packages"]["com.pluxy.tv"][side][field] = value
    with pytest.raises(ValueError, match="identity differs"):
        publisher.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes", transition)
    assert all(e[1] == "GET" for e in publisher.events)


def test_transition_source_commit_and_release_id_cannot_be_reused(distribution, transition_setup):
    publisher, transition = transition_setup
    transition["from_commit"] = SHA  # The alias moved to the new source after a completed transition.
    with pytest.raises(ValueError, match="different source commit"):
        publisher.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes", transition)
    assert all(e[1] == "GET" for e in publisher.events)
    transition["from_commit"] = OLD_SHA
    transition["from_release_id"] = 99
    publisher.events.clear()
    with pytest.raises(ValueError, match="current debug release"):
        publisher.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes", transition)
    assert all(e[1] == "GET" for e in publisher.events)


def test_incomplete_legacy_archive_keeps_old_alias_and_prevents_new_candidate(distribution, transition_setup):
    publisher, transition = transition_setup
    publisher.archive_failure = True
    with pytest.raises(ValueError, match="legacy upload incomplete"):
        publisher.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes", transition)
    assert publisher.archive["draft"] is True
    assert not any(e[0] == "releases/2" or (e[0] == "verified-upload" and e[1].startswith("dev-aaaaaaaa"))
                   for e in publisher.events)


def test_verified_legacy_archive_is_reused_without_overwriting_it(distribution, transition_setup):
    publisher, transition = transition_setup
    info = json.loads((distribution / "BUILD_INFO.json").read_text())
    publisher.archive_debug_transition(publisher.current, info, Path("sdk"), transition)
    publisher.events.clear()
    publisher.archive_debug_transition(publisher.current, info, Path("sdk"), transition)
    assert not any(e[0] == "verified-upload" or e[1] in ("POST", "PATCH") for e in publisher.events)


def test_alias_change_during_archive_prevents_mutation(distribution, transition_setup):
    publisher, transition = transition_setup
    original_upload = publisher.upload

    def upload(tag, release_id, files, clobber=False):
        original_upload(tag, release_id, files, clobber)
        publisher.changed_source = True

    publisher.upload = upload
    with pytest.raises(ValueError, match="assets have changed"):
        publisher.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes", transition)
    assert publisher.archive["draft"] is True
    assert not any(e[0] == "releases/2" for e in publisher.events)


def test_transition_refuses_corrupt_legacy_download_and_missing_apk(distribution, transition_setup):
    publisher, transition = transition_setup
    publisher.current["assets"][0]["digest"] = "sha256:" + "e" * 64
    with pytest.raises(ValueError, match="download differs"):
        publisher.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes", transition)
    assert all(e[1] == "GET" for e in publisher.events)
    publisher.current["assets"].pop(0)
    # The fixture downloader indexes IDs; refuse earlier by clearing all assets instead.
    publisher.current["assets"].clear()
    with pytest.raises(ValueError, match="Both previous"):
        publisher.publish(distribution, "dev-aaaaaaaaaaaa-1-1", SHA, Path("sdk"), True, "notes", transition)
    assert all(e[1] == "GET" for e in publisher.events)


def test_transition_is_not_available_to_official_releases(distribution, transition_setup):
    publisher, transition = transition_setup
    info = json.loads((distribution / "BUILD_INFO.json").read_text())
    info["channel"] = "release"
    (distribution / "BUILD_INFO.json").write_text(json.dumps(info))
    with pytest.raises(ValueError, match="only permitted for the debug alias"):
        publisher.publish(distribution, "v1.5.1", SHA, Path("sdk"), False, "notes", transition)
    assert not publisher.events
