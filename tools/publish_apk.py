"""Verify Android artifacts and publish complete, attributable GitHub releases.

The workflow is responsible for the test/lint gates and for signing. This tool
never generates keys. A mutable alias is hidden as a draft while assets change;
the immutable candidate stays available if an alias update fails.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path

PACKAGES = {"com.pluxy.tv": "PLAYER", "com.pluxy.server": "SERVER"}
IDENTITY_FIELDS = {"version_name", "version_code", "signing_cert_sha256"}
REINSTALL_NOTICE = (
    "**Changement de clé de signature : une ancienne installation ne peut pas être mise à jour "
    "avec ces APK.** Sauvegardez vos réglages si possible, puis désinstallez l'ancienne application "
    "et installez celle-ci. La désinstallation peut effacer les données locales. Les prochaines "
    "versions conserveront cette nouvelle clé."
)


def signing_identity(artifact: dict) -> dict:
    return {field: artifact[field] for field in IDENTITY_FIELDS}


def validate_transition(value: dict, repository: str, info: dict) -> None:
    """A manual, exact old-to-new identity authorization; never a general bypass."""
    fields = {"schema", "repository", "from_release_id", "from_commit", "to_commit", "packages"}
    if not isinstance(value, dict) or set(value) != fields:
        raise ValueError("Invalid signing transition fields")
    if (value["schema"] != 1 or value["repository"] != repository
            or value["to_commit"] != info["commit"]
            or not isinstance(value["from_commit"], str)
            or not re.fullmatch(r"[a-f0-9]{40}", value["from_commit"])
            or type(value["from_release_id"]) is not int or value["from_release_id"] <= 0
            or not isinstance(value["packages"], dict) or set(value["packages"]) != set(PACKAGES)):
        raise ValueError("Signing transition must identify the repository, releases and both packages")
    artifacts = {a["package"]: a for a in info["artifacts"] if a["name"].endswith(".apk")}
    for package, identities in value["packages"].items():
        if not isinstance(identities, dict) or set(identities) != {"from", "to"}:
            raise ValueError("Signing transition needs exact from/to identities")
        for identity in identities.values():
            if (not isinstance(identity, dict) or set(identity) != IDENTITY_FIELDS
                    or not isinstance(identity["version_name"], str) or not identity["version_name"]
                    or type(identity["version_code"]) is not int or identity["version_code"] <= 0
                    or not isinstance(identity["signing_cert_sha256"], list)
                    or len(identity["signing_cert_sha256"]) != 1
                    or not isinstance(identity["signing_cert_sha256"][0], str)
                    or not re.fullmatch(r"[a-f0-9]{64}", identity["signing_cert_sha256"][0])):
                raise ValueError("Signing transition contains an invalid APK identity")
        if identities["to"] != signing_identity(artifacts[package]):
            raise ValueError(f"Signing transition new identity differs from the APK: {package}")
        if (identities["from"]["signing_cert_sha256"] == identities["to"]["signing_cert_sha256"]
                or identities["from"]["version_code"] >= identities["to"]["version_code"]):
            raise ValueError("Signing transition must change the certificate and increase versionCode")


def run(*args: str, binary: bool = False) -> str | bytes:
    result = subprocess.run(args, capture_output=True, timeout=180, check=False)
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout if binary else result.stdout.decode(errors="replace")


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inspect_apk(path: Path, build_tools: Path, require_release: bool = False) -> dict:
    suffix = ".bat" if os.name == "nt" else ""
    badging = run(str(build_tools / ("aapt.exe" if os.name == "nt" else "aapt")),
                  "dump", "badging", str(path))
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    if require_release and re.search(r"^application-debuggable", badging, re.M):
        raise ValueError(f"Debuggable APK cannot enter the official release: {path.name}")
    certs = run(str(build_tools / f"apksigner{suffix}"), "verify", "--print-certs", str(path))
    fingerprints = sorted(set(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})", certs)))
    if not package or not sdk or not fingerprints or package[1] not in PACKAGES:
        raise ValueError(f"Unrecognized APK identity or signature: {path.name}")
    return {"name": path.name, "package": package[1], "version_name": package[3],
            "version_code": int(package[2]), "min_sdk": int(sdk[1]),
            "sha256": digest(path), "signing_cert_sha256": [c.lower() for c in fingerprints]}


def checksums(directory: Path) -> None:
    files = sorted(p for p in directory.iterdir() if p.name != "SHA256SUMS.txt")
    if any(not p.is_file() or p.is_symlink() for p in files):
        raise ValueError("Artifact directory must contain only regular files")
    (directory / "SHA256SUMS.txt").write_text(
        "".join(f"{digest(p)}  {p.name}\n" for p in files), encoding="utf-8")


def manifest(directory: Path, build_tools: Path, channel: str, commit: str, run_url: str | None) -> dict:
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("A full source commit SHA is required")
    apks = [inspect_apk(p, build_tools, require_release=channel == "release")
            for p in sorted(directory.glob("*.apk"))]
    if len(apks) != 2 or {a["package"] for a in apks} != set(PACKAGES):
        raise ValueError("Both unique Pluxy APKs are required")
    if len({(a["version_name"], a["version_code"]) for a in apks}) != 1:
        raise ValueError("Player and server versions differ")
    for artifact in apks:
        expected = os.environ.get(f"{PACKAGES[artifact['package']]}_CERT_SHA256", "").lower()
        if expected and artifact["signing_cert_sha256"] != [expected]:
            raise ValueError(f"APK signer differs from the supplied keystore: {artifact['name']}")
    artifacts = list(apks)
    for aab in sorted(directory.glob("*.aab")):
        package = "com.pluxy.server" if aab.name.startswith("Pluxy-Server-Android-") else "com.pluxy.tv"
        apk = next(a for a in apks if a["package"] == package)
        # jarsigner verification against the corresponding keystore is a workflow
        # prerequisite. The paired APK supplies the same variant's manifest data.
        artifacts.append({**apk, "name": aab.name, "sha256": digest(aab)})
    value = {"schema": 1, "commit": commit, "run_url": run_url, "channel": channel,
             "created_at": datetime.now(timezone.utc).isoformat(), "artifacts": artifacts}
    (directory / "BUILD_INFO.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    checksums(directory)
    return value


class Publisher:
    def __init__(self, repository: str):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("Invalid GitHub repository")
        self.repository = repository

    def api(self, endpoint: str, method: str = "GET", payload: dict | None = None,
            missing_ok: bool = False) -> dict | None:
        args = ["gh", "api", f"repos/{self.repository}/{endpoint}", "--method", method]
        if payload is not None:
            args += ["--input", "-"]
        result = subprocess.run(args, input=json.dumps(payload).encode() if payload is not None else None,
                                capture_output=True, timeout=180, check=False)
        if result.returncode:
            error = result.stderr.decode(errors="replace")
            if missing_ok and "(HTTP 404)" in error:
                return None
            raise RuntimeError(f"GitHub {method} {endpoint} failed: {error.strip()}")
        return json.loads(result.stdout)

    def require_main(self, commit: str) -> None:
        if self.api("git/ref/heads/main")["object"]["sha"] != commit:
            raise ValueError("Publication source is no longer the current main commit")

    def release(self, tag: str) -> dict | None:
        return self.api(f"releases/tags/{tag}", missing_ok=True)

    def require_tag_commit(self, tag: str, commit: str, required: bool = False) -> None:
        ref = self.api(f"git/ref/tags/{tag}", missing_ok=True)
        if ref is None:
            if required:
                raise ValueError(f"Required source tag is absent: {tag}")
            return  # GitHub may create a new tag from target_commitish.
        target = ref["object"]
        for _ in range(16):
            if target["type"] == "commit":
                if target["sha"] != commit:
                    raise ValueError(f"Existing tag {tag} points to a different source commit")
                return
            if target["type"] != "tag":
                raise ValueError(f"Existing tag {tag} does not resolve to a commit")
            target = self.api(f"git/tags/{target['sha']}")["object"]
        raise ValueError(f"Existing tag {tag} exceeds the annotated-tag nesting limit")

    def require_unused_tag(self, tag: str, commit: str) -> None:
        if self.release(tag) is not None:
            raise ValueError(f"Release already exists: {tag}")
        # A tag-triggered build already has a tag; a manual build may encounter
        # one. target_commitish never moves that ref, so both must match source.
        self.require_tag_commit(tag, commit)

    def verify_assets(self, release_id: int, files: list[Path]) -> None:
        remote = {asset["name"]: asset for asset in self.api(f"releases/{release_id}")["assets"]}
        for path in files:
            asset = remote.get(path.name)
            if not asset or asset.get("state") != "uploaded" or asset["size"] != path.stat().st_size:
                raise ValueError(f"Incomplete remote asset: {path.name}")
            actual = asset.get("digest")
            if not actual:
                data = run("gh", "api", f"repos/{self.repository}/releases/assets/{asset['id']}",
                           "-H", "Accept: application/octet-stream", binary=True)
                actual = "sha256:" + hashlib.sha256(data).hexdigest()
            if actual != "sha256:" + digest(path):
                raise ValueError(f"Remote checksum differs: {path.name}")

    def upload(self, tag: str, release_id: int, files: list[Path], clobber: bool = False) -> None:
        run("gh", "release", "upload", tag, *(str(p) for p in files),
            "--repo", self.repository, *(["--clobber"] if clobber else []))
        self.verify_assets(release_id, files)

    def create_complete(self, tag: str, commit: str, title: str, notes: str,
                        files: list[Path], prerelease: bool) -> dict:
        self.require_unused_tag(tag, commit)
        draft = self.api("releases", "POST", {"tag_name": tag, "target_commitish": commit,
                         "name": title, "body": notes, "draft": True, "prerelease": prerelease})
        self.upload(tag, draft["id"], files)
        self.require_main(commit)
        self.require_tag_commit(tag, commit)
        return self.api(f"releases/{draft['id']}", "PATCH", {"draft": False})

    def check_debug_compatibility(self, current: dict | None, info: dict, build_tools: Path) -> None:
        if current is None:
            return
        by_name = {a["name"]: a for a in info["artifacts"] if a["name"].endswith(".apk")}
        with tempfile.TemporaryDirectory(prefix="pluxy-release-check-") as temp:
            for name, artifact in by_name.items():
                run("gh", "release", "download", "dev-latest", "--repo", self.repository,
                    "--pattern", name, "--dir", temp)
                previous = inspect_apk(Path(temp) / name, build_tools)
                if previous["package"] != artifact["package"] or previous["signing_cert_sha256"] != artifact["signing_cert_sha256"]:
                    raise ValueError(f"Existing debug certificate is incompatible: {name}")
                if previous["version_code"] > artifact["version_code"]:
                    raise ValueError(f"Debug versionCode would downgrade: {name}")

    def require_transition_source(self, current: dict, transition: dict) -> None:
        actual = self.release("dev-latest")
        if not actual or actual["id"] != transition["from_release_id"] or actual.get("draft"):
            raise ValueError("Signing transition old release is absent or has changed")
        # Asset IDs identify the downloaded bytes, rather than a mutable filename.
        fields = ("id", "name", "size", "digest")
        before = sorted(tuple(asset.get(field) for field in fields) for asset in current["assets"])
        after = sorted(tuple(asset.get(field) for field in fields) for asset in actual["assets"])
        if before != after:
            raise ValueError("Signing transition old release assets have changed")
        self.require_tag_commit("dev-latest", transition["from_commit"], required=True)

    def archive_debug_transition(self, current: dict | None, info: dict, build_tools: Path,
                                 transition: dict) -> dict:
        if not current or current["id"] != transition["from_release_id"]:
            raise ValueError("Signing transition does not identify the current debug release")
        self.require_transition_source(current, transition)
        tag = f"dev-legacy-{transition['from_commit'][:12]}-{transition['from_release_id']}"
        by_name = {a["name"]: a for a in info["artifacts"] if a["name"].endswith(".apk")}
        with tempfile.TemporaryDirectory(prefix="pluxy-signing-archive-") as temp:
            directory = Path(temp)
            seen = set()
            for asset in current["assets"]:
                name = asset["name"]
                if (not re.fullmatch(r"[A-Za-z0-9_.-]+", name) or name in seen
                        or asset.get("state") != "uploaded"):
                    raise ValueError("Unsafe or incomplete legacy release asset")
                seen.add(name)
                path = directory / name
                path.write_bytes(run("gh", "api", f"repos/{self.repository}/releases/assets/{asset['id']}",
                                     "-H", "Accept: application/octet-stream", binary=True))
                if (path.stat().st_size != asset["size"]
                        or (asset.get("digest") and asset["digest"] != "sha256:" + digest(path))):
                    raise ValueError(f"Legacy asset download differs: {name}")
                if name in by_name:
                    previous = inspect_apk(path, build_tools)
                    package = by_name[name]["package"]
                    if (previous["package"] != package
                            or signing_identity(previous) != transition["packages"][package]["from"]):
                        raise ValueError(f"Signing transition old identity differs from the APK: {name}")
            if not set(by_name).issubset(seen):
                raise ValueError("Both previous debug APKs are required for a signing transition")
            files = sorted(directory.iterdir())
            archive = self.release(tag)
            if archive:
                self.require_tag_commit(tag, transition["from_commit"])
                if {a["name"] for a in archive["assets"]} != seen:
                    raise ValueError("Existing legacy archive has different assets")
                self.verify_assets(archive["id"], files)
            else:
                self.require_unused_tag(tag, transition["from_commit"])
                notes = ("Archive immuable des APK précédant le changement de clé de signature. "
                         "Ces fichiers conservent leur signature et leurs sommes de contrôle d'origine. "
                         "Ce canal historique ne recevra plus de mises à jour.\n\n"
                         f"Commit historique : `{transition['from_commit']}`\n\n"
                         f"Release d'origine : {current['html_url']}")
                archive = self.api("releases", "POST", {"tag_name": tag,
                                   "target_commitish": transition["from_commit"],
                                   "name": "Pluxy — ancienne clé de signature", "body": notes,
                                   "draft": True, "prerelease": True})
                self.upload(tag, archive["id"], files)
            self.require_main(info["commit"])
            self.require_transition_source(current, transition)
            self.require_tag_commit(tag, transition["from_commit"])
            if archive["draft"]:
                archive = self.api(f"releases/{archive['id']}", "PATCH", {"draft": False})
            return archive

    def publish(self, directory: Path, tag: str, commit: str, build_tools: Path,
                alias: bool, notes: str, transition: dict | None = None) -> None:
        info = json.loads((directory / "BUILD_INFO.json").read_text(encoding="utf-8"))
        if info["schema"] != 1 or info["commit"] != commit or info["channel"] != ("debug" if alias else "release"):
            raise ValueError("Build metadata does not match this publication")
        if not re.fullmatch(r"dev-[a-f0-9]{12}-\d+-\d+" if alias else r"v\d+\.\d+\.\d+", tag):
            raise ValueError("Invalid immutable release tag")
        if alias and not tag.startswith("dev-" + commit[:12] + "-"):
            raise ValueError("Immutable tag and source commit differ")
        files = sorted(directory.iterdir())
        for path in files:
            if not path.is_file() or path.is_symlink() or not re.fullmatch(r"[A-Za-z0-9_.-]+", path.name):
                raise ValueError("Unsafe publication artifact")
        for artifact in info["artifacts"]:
            path = directory / artifact["name"]
            if path not in files or digest(path) != artifact["sha256"]:
                raise ValueError("Build artifact differs from its manifest")
            if path.suffix == ".apk" and inspect_apk(path, build_tools, require_release=not alias) != artifact:
                raise ValueError("APK identity differs from its manifest")
        apk_packages = [a["package"] for a in info["artifacts"] if a["name"].endswith(".apk")]
        if len(apk_packages) != 2 or set(apk_packages) != set(PACKAGES):
            raise ValueError("Both unique Pluxy APKs are required for publication")
        if not alias and any("v" + a["version_name"] != tag for a in info["artifacts"]):
            raise ValueError("Release tag and APK versions differ")
        if transition is not None:
            if not alias:
                raise ValueError("Signing transition is only permitted for the debug alias")
            validate_transition(transition, self.repository, info)
        checksums(directory)
        self.require_main(commit)
        current = self.release("dev-latest") if alias else None
        if alias:
            if transition is None:
                self.check_debug_compatibility(current, info, build_tools)
            else:
                archive = self.archive_debug_transition(current, info, build_tools, transition)
                notes += f"\n\n{REINSTALL_NOTICE}\n\nAncienne clé (archive) : {archive['html_url']}"
                info["signing_transition"] = {**transition, "legacy_release_url": archive["html_url"]}
                (directory / "BUILD_INFO.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
                checksums(directory)
        files = sorted(directory.iterdir())
        candidate = self.create_complete(tag, commit, tag, notes, files, prerelease=alias)
        if not alias:
            return
        self.require_main(commit)
        if transition is not None:
            self.require_transition_source(current, transition)
        body = f"{notes}\n\nCommit: `{commit}`\n\nBuild immuable: {candidate['html_url']}\n\nRun: {info['run_url']}"
        if current:
            draft = self.api(f"releases/{current['id']}", "PATCH", {"draft": True})
        else:
            draft = self.api("releases", "POST", {"tag_name": "dev-latest", "target_commitish": commit,
                             "draft": True, "prerelease": True})
        self.upload("dev-latest", draft["id"], files, clobber=True)
        self.require_main(commit)
        # --target does not move an existing tag. Only the documented mutable
        # alias may be moved; immutable tags are never overwritten.
        if self.api("git/ref/tags/dev-latest", missing_ok=True):
            self.api("git/refs/tags/dev-latest", "PATCH", {"sha": commit, "force": True})
        else:
            self.api("git/refs", "POST", {"ref": "refs/tags/dev-latest", "sha": commit})
        self.api(f"releases/{draft['id']}", "PATCH", {"name": "Pluxy — debug continu", "body": body,
                 "target_commitish": commit, "prerelease": True, "draft": False})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["manifest", "publish-debug", "publish-release"])
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--channel", choices=["debug", "release"], default="debug")
    parser.add_argument("--run-url")
    parser.add_argument("--tag")
    parser.add_argument("--notes-file", type=Path)
    parser.add_argument("--signing-transition-file", type=Path,
                        help="Manual one-time debug transition with exact old/new APK identities")
    args = parser.parse_args()
    if args.signing_transition_file and args.command != "publish-debug":
        parser.error("--signing-transition-file is only valid for publish-debug")
    if args.command == "manifest":
        manifest(args.directory, args.build_tools, args.channel, args.commit, args.run_url)
    else:
        if not args.tag:
            parser.error("--tag is required for publication")
        notes = args.notes_file.read_text(encoding="utf-8") if args.notes_file else "APK de test validés ; hors Google Play."
        publisher = Publisher(os.environ["GITHUB_REPOSITORY"])
        transition = (json.loads(args.signing_transition_file.read_text(encoding="utf-8"))
                      if args.signing_transition_file else None)
        publisher.publish(args.directory, args.tag, args.commit, args.build_tools,
                          alias=args.command == "publish-debug", notes=notes, transition=transition)


if __name__ == "__main__":
    main()
