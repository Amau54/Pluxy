"""Build and test on Windows; GitHub only receives source and verified assets."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools import publish_apk as apk

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "Amau54/Pluxy"


def execute(*args: str) -> None:
    print("[Pluxy] " + " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=ROOT, check=True)


def clean_commit() -> str:
    if apk.run("git", "-C", str(ROOT), "status", "--porcelain").strip():
        raise ValueError("Enregistrez les modifications dans Git avant la compilation finale.")
    if apk.run("git", "-C", str(ROOT), "branch", "--show-current").strip() != "main":
        raise ValueError("La publication locale doit partir de main.")
    tracked = apk.run("git", "-C", str(ROOT), "ls-files", "-z").split("\0")
    validate_source_files(ROOT, [name for name in tracked if name])
    return apk.run("git", "-C", str(ROOT), "rev-parse", "HEAD").strip()


def validate_source_files(root: Path, tracked: list[str]) -> None:
    for name in tracked:
        if Path(name).suffix.lower() in (".jks", ".keystore", ".p12"):
            raise ValueError("Une cle de signature est suivie par Git ; publication refusee.")
        if name.startswith("client/") and name.endswith("build.gradle.kts"):
            if re.search(r'(storePassword|keyPassword)\s*=\s*"[^"]+"',
                         (root / name).read_text(encoding="utf-8")):
                raise ValueError("Mot de passe de signature litteral dans Gradle ; publication refusee.")


def powershell() -> str:
    shell = shutil.which("pwsh.exe") or shutil.which("powershell.exe")
    if not shell:
        raise ValueError("La compilation locale requiert Windows et PowerShell.")
    return shell


def validate_windows(directory: Path, commit: str, version: str) -> list[Path]:
    info = json.loads((directory / "PC_BUILD_INFO.json").read_text(encoding="utf-8-sig"))
    setup = directory / "Pluxy-Setup.exe"
    checksum = apk.digest(setup)
    if (info.get("schema") != 1 or info.get("dirty") is not False
            or info.get("commit") != commit or info.get("version") != version
            or info.get("sha256") != checksum or info.get("size") != setup.stat().st_size):
        raise ValueError("Installateur Windows modifie ou provenance incoherente.")
    if (directory / "PC_SHA256SUMS.txt").read_text().strip() != checksum + "  Pluxy-Setup.exe":
        raise ValueError("Somme de controle Windows incoherente.")
    return [setup, directory / "PC_BUILD_INFO.json", directory / "PC_SHA256SUMS.txt"]


def publish_windows(publisher: apk.Publisher, directory: Path, commit: str,
                    version: str, stamp: int) -> None:
    files = validate_windows(directory, commit, version)
    publisher.require_main(commit)
    notes = "Installez Pluxy-Setup.exe, puis choisissez Lecteur ou Serveur au premier lancement."
    candidate = publisher.create_complete(f"pc-v{version}-{commit[:7]}-{stamp}", commit,
                                          f"Pluxy Windows {version}", notes, files, prerelease=False)
    publisher.require_main(commit)
    current = publisher.release("pc-latest")
    if current:
        draft = publisher.api(f"releases/{current['id']}", "PATCH", {"draft": True})
    else:
        draft = publisher.api("releases", "POST", {"tag_name": "pc-latest", "target_commitish": commit,
                              "draft": True, "prerelease": False})
    publisher.upload("pc-latest", draft["id"], files, clobber=True)
    publisher.require_main(commit)
    if publisher.api("git/ref/tags/pc-latest", missing_ok=True):
        publisher.api("git/refs/tags/pc-latest", "PATCH", {"sha": commit, "force": True})
    else:
        publisher.api("git/refs", "POST", {"ref": "refs/tags/pc-latest", "sha": commit})
    publisher.api(f"releases/{draft['id']}", "PATCH", {
        "draft": False, "prerelease": False, "name": f"Pluxy Windows {version}",
        "body": notes + f"\n\nBuild local : {candidate['html_url']}", "target_commitish": commit,
    })


def build_and_test(target: str, directory: Path, commit: str) -> Path | None:
    python = sys.executable
    execute(python, "tools/sync_versions.py", "--check")
    execute(python, "-m", "pytest", "-q", "server/tests", "tools/tests")
    execute(python, "-m", "ruff", "check", "server/pluxy", "server/tests", "tools")
    execute(python, "-m", "pip_audit", "-r", "server/requirements.txt", "--strict")
    build_tools = None
    shell = powershell()
    options = ("-NoProfile", "-ExecutionPolicy", "RemoteSigned")
    if target in ("both", "android"):
        execute(shell, *options, "-File", "tools/Build-Android.ps1", "-Verify")
        local = json.loads((ROOT / "dist/BUILD_INFO-player-debug.json").read_text(encoding="utf-8-sig"))
        if (local["commit"] != commit or local["dirty"] is not False
                or local["verified"] is not True):
            raise ValueError("APK sans verification locale ou provenant de sources modifiees.")
        android = directory / "android"
        android.mkdir()
        shutil.copyfile(ROOT / "dist" / local["artifacts"][0]["name"], android / "Pluxy-debug.apk")
        # Resolve the same SDK/JDK as Build-Android; credentials stay outside Git.
        command = (f". '{ROOT / 'tools/Android-Deployment.ps1'}'; "
                   f"$resolved = Find-AndroidEnvironment '{ROOT}'; "
                   "$resolved | ConvertTo-Json -Compress")
        resolved = json.loads(apk.run(shell, *options, "-Command", command))
        os.environ["JAVA_HOME"] = str(Path(resolved["Java"]).parent.parent)
        build_tools = Path(resolved["Aapt"]).parent
        info = apk.manifest(android, build_tools, "debug", commit,
                            f"https://github.com/{REPOSITORY}/commit/{commit}")
        if info["artifacts"] != [{**local["artifacts"][0], "name": "Pluxy-debug.apk"}]:
            raise ValueError("Identite de l'APK differente apres preparation.")
    if target in ("both", "windows"):
        windows = directory / "windows"
        execute(shell, *options, "-File", "tools/Build-PC.ps1", "-OutputDirectory", windows)
        execute(shell, *options, "-File", "tools/Test-PC.ps1", "-AppDirectory", windows / "app",
                "-OutputDirectory", directory / "windows-tests")
        versions = json.loads((ROOT / "versions.json").read_text())
        validate_windows(windows, commit, versions["windows"]["name"])
    return build_tools


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["both", "android", "windows"], default="both")
    parser.add_argument("--publish", action="store_true", help="Push main et publier apres les tests locaux")
    args = parser.parse_args()
    commit = clean_commit()
    if args.publish:
        execute("gh", "auth", "status")
        origin = apk.run("git", "-C", str(ROOT), "remote", "get-url", "origin").strip()
        if origin not in (f"https://github.com/{REPOSITORY}.git", f"git@github.com:{REPOSITORY}.git"):
            raise ValueError("Le depot origin ne correspond pas au depot de publication.")
    stamp = time.time_ns() // 1_000_000
    directory = ROOT / "artifacts/local-releases" / f"{commit[:12]}-{stamp}"
    directory.mkdir(parents=True)
    build_tools = build_and_test(args.target, directory, commit)
    if clean_commit() != commit:
        raise ValueError("Les sources ont change pendant les tests ; aucune publication.")
    if args.publish:
        execute("git", "push", "origin", "main:main")
        publisher = apk.Publisher(REPOSITORY)
        publisher.require_main(commit)
        if build_tools:
            publisher.publish(directory / "android", f"dev-{commit[:12]}-{stamp}-1", commit,
                              build_tools, alias=True, notes="Application compilee et testee localement.")
        if args.target in ("both", "windows"):
            versions = json.loads((ROOT / "versions.json").read_text())
            publish_windows(publisher, directory / "windows", commit, versions["windows"]["name"], stamp)
    print(f"[Pluxy] {'Publie' if args.publish else 'Valide localement, sans envoi GitHub'} : {directory}")


if __name__ == "__main__":
    main()
