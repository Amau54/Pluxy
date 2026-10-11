"""Protect publication boundaries without adding a YAML runtime dependency."""
from pathlib import Path
import re


WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/build-apk.yml"


def jobs():
    source = WORKFLOW.read_text(encoding="utf-8")
    sections = re.split(r"(?m)^  ([a-z][a-z-]*):\n", source.split("\njobs:\n", 1)[1])
    return source, dict(zip(sections[1::2], sections[2::2]))


def test_quick_apks_are_built_once_and_publication_requires_all_gates():
    source, sections = jobs()
    assert ":app:assembleDebug" in sections["apk"]
    assert "serverapp/build/outputs/apk" not in source
    assert "assembleDebug" not in sections["android"] + sections["dev-release"]
    for name in ("dev-release", "release"):
        dependencies = re.search(r"(?m)^    needs: \[([^]]+)\]$", sections[name])
        assert dependencies is not None
        assert {"python", "android", "apk", "android-startup"} <= {
            value.strip() for value in dependencies.group(1).split(",")
        }
    assert "artifact-ids: ${{ needs.apk.outputs.artifact_id }}" in sections["dev-release"]
    assert "secrets." not in sections["apk"] + sections["android"] + sections["android-startup"]
    assert "github.event_name == 'push' && github.ref == 'refs/heads/main'" in sections["dev-release"]
    assert "environment: play-release" in sections["release"]
    assert "cancel-in-progress: ${{ github.event_name == 'pull_request' }}" in source


def test_tests_lint_r8_and_required_job_name_survive_fast_path():
    _, sections = jobs()
    gate = sections["android"]
    assert "name: Android - tests, lint et bundles" in gate
    for task in (":app:testDebugUnitTest", ":serverapp:testDebugUnitTest",
                 ":receiver-core:testDebugUnitTest", ":receiver-dlna:testDebugUnitTest",
                 ":app:lintRelease", ":serverapp:lintRelease", ":receiver-core:lintRelease",
                 ":receiver-dlna:lintRelease", ":receiver-cast:lintRelease",
                 ":app:bundleRelease"):
        assert task in gate
    assert "pytest -q server/tests tools/tests" in sections["python"]


def test_only_unpublished_control_apks_generate_a_runner_key():
    _, sections = jobs()
    assert 'keytool -genkeypair -keystore "$HOME/.android/debug.keystore"' in sections["apk"]
    assert 'if [[ ! -f "$HOME/.android/debug.keystore" ]]' in sections["apk"]
    assert sections["apk"].index("keytool -genkeypair") < sections["apk"].index(":app:assembleDebug")
    assert 'keytool -genkeypair -keystore "$HOME/.android/debug.keystore"' in sections["android-startup"]
    assert sections["android-startup"].index("keytool -genkeypair") < sections["android-startup"].index(":app:connectedDebugAndroidTest")
    assert "-genkeypair" not in sections["dev-release"] + sections["release"]


def test_pinned_actions_build_cache_stable_signing_and_private_mapping():
    source, sections = jobs()
    for action in re.findall(r"uses: ([^\s#]+)", source):
        assert re.fullmatch(r"[\w/-]+@[a-f0-9]{40}", action), action
    assert source.count("cache: gradle") == 0
    for job in ("apk", "android", "release"):
        assert "gradle/actions/setup-gradle@" in sections[job]
        assert "--build-cache" in sections[job]
    assert "cache-read-only: true" in sections["release"]
    for owner in ("PLAYER",):
        for field in ("JKS_BASE64", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"):
            assert f"secrets.PLUXY_{owner}_DEBUG_{field}" in sections["dev-release"]
    assert "PLUXY_SERVER_DEBUG" not in sections["dev-release"]
    assert "ready=false" in sections["dev-release"] and "::warning::" in sections["dev-release"]
    assert "apksigner" in sections["dev-release"] and "publish-debug" in sections["dev-release"]
    assert "jarsigner -verify -strict -keystore" in sections["release"]
    assert sections["release"].index("Verifier version") < sections["release"].index("Verifier et charger")
    assert sections["release"].index("Nommer et verifier") < sections["release"].index("Effacer les cles")
    assert "path: dist-mapping/" in sections["release"]
    assert "publish-release" in sections["release"]
