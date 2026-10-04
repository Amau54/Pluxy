"""Exercise the deployment helpers in real PowerShell processes.

On Linux, these integration tests are skipped when neither pwsh nor PowerShell
is installed. No Android device, SDK, Gradle invocation or APK installation is
required; entry-point failures use invalid arguments before their bodies run.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest


TOOLS = Path(__file__).resolve().parents[1]
HELPERS = TOOLS / "Android-Deployment.ps1"
SCRIPTS = ("Android-Deployment.ps1", "Build-Android.ps1", "Install-Android.ps1")


def _shells() -> list[str | None]:
    found = []
    for names in (("pwsh", "pwsh.exe"), ("powershell", "powershell.exe")):
        executable = next(
            (path for name in names if (path := shutil.which(name))), None
        )
        if executable is not None and executable not in found:
            found.append(executable)
    return found or [None]


@pytest.fixture(scope="module", params=_shells())
def powershell(request: pytest.FixtureRequest) -> str:
    if request.param is None:
        pytest.skip(
            "PowerShell absent: install pwsh to run deployment integration tests on Linux"
        )
    return request.param


def _run(
    shell: str, code: str, payload: object = None
) -> subprocess.CompletedProcess[str]:
    encoded = base64.b64encode(code.encode("utf-16-le")).decode("ascii")
    return subprocess.run(
        [
            shell,
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-EncodedCommand",
            encoded,
        ],
        input=json.dumps(payload),
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
        check=False,
    )


def _json_result(shell: str, body: str, payload: dict) -> object:
    code = (
        """
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
$inputData = [Console]::In.ReadToEnd() | ConvertFrom-Json
. $inputData.helpers
"""
        + body
    )
    result = _run(shell, code, {"helpers": str(HELPERS), **payload})
    assert result.returncode == 0, f"{result.stdout}\n{result.stderr}"
    return json.loads(result.stdout)


def _apk(
    *,
    version: int = 12,
    package: str = "com.pluxy.tv",
    certificate: str = "a",
    sdk: int = 23,
) -> dict:
    return {
        "Package": package,
        "VersionCode": version,
        "VersionName": f"1.0.{version}",
        "MinSdk": sdk,
        "Certificates": [certificate * 64],
    }


UPDATE_CASES = {
    "upgrade": (_apk(version=13), _apk(), 36, None),
    "same_version": (_apk(), _apk(), 36, None),
    "first_install": (_apk(), None, 23, None),
    "wrong_certificate": (_apk(certificate="b"), _apk(), 36, "SIGNATURE INCOMPATIBLE"),
    "downgrade": (_apk(version=11), _apk(), 36, "VERSION PLUS ANCIENNE"),
    "wrong_package": (
        _apk(package="com.pluxy.server"),
        _apk(),
        36,
        "autre application",
    ),
    "older_android_update": (_apk(sdk=24), _apk(), 23, "Android trop ancien"),
    "older_android_first_install": (_apk(), None, 22, "Android trop ancien"),
}


@pytest.fixture(scope="module")
def update_results(powershell: str) -> dict:
    cases = [
        {"name": name, "candidate": candidate, "installed": installed, "sdk": sdk}
        for name, (candidate, installed, sdk, _) in UPDATE_CASES.items()
    ]
    results = _json_result(
        powershell,
        """
$results = foreach ($case in $inputData.cases) {
    try {
        Assert-AndroidUpdate $case.candidate $case.installed ([int]$case.sdk)
        [pscustomobject]@{ name = $case.name; accepted = $true; message = $null }
    } catch {
        [pscustomobject]@{ name = $case.name; accepted = $false; message = $_.Exception.Message }
    }
}
ConvertTo-Json -InputObject @($results) -Depth 4 -Compress
""",
        {"cases": cases},
    )
    return {result["name"]: result for result in results}


@pytest.mark.parametrize("name", UPDATE_CASES)
def test_update_accepts_compatible_versions_and_refuses_unsafe_replacements(
    update_results: dict, name: str
) -> None:
    error = UPDATE_CASES[name][3]
    result = update_results[name]
    assert result["accepted"] is (error is None)
    if error is not None:
        assert error in result["message"]
    if name == "wrong_certificate":
        assert "Donnees conservees" in result["message"]


FAILURE_CASES = {
    "certificate": (
        "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]",
        "Signature incompatible",
    ),
    "certificate_detail": (
        "Package signatures do not match the previously installed version",
        "Signature incompatible",
    ),
    "downgrade": ("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]", "plus ancienne"),
    "disk": ("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]", "stockage insuffisant"),
    "restricted": ("Failure [INSTALL_FAILED_USER_RESTRICTED]", "Installation refusee"),
    "android": ("Failure [INSTALL_FAILED_OLDER_SDK]", "Android trop ancienne"),
    "unauthorized": (
        "error: device unauthorized. Please check the confirmation dialog",
        "ADB non autorise",
    ),
    "offline": ("error: device offline", "deconnecte ou hors ligne"),
    "missing_device": (
        "error: device emulator-9999 not found",
        "deconnecte ou hors ligne",
    ),
    "no_devices": ("error: no devices/emulators found", "deconnecte ou hors ligne"),
    "unknown": (
        "Failure [INSTALL_FAILED_INVALID_APK: bad split]",
        "INSTALL_FAILED_INVALID_APK: bad split",
    ),
}


@pytest.fixture(scope="module")
def failure_results(powershell: str) -> dict:
    results = _json_result(
        powershell,
        """
$results = foreach ($case in $inputData.cases) {
    [pscustomobject]@{ name = $case.name; message = (Get-AndroidInstallFailure $case.text) }
}
ConvertTo-Json -InputObject @($results) -Depth 3 -Compress
""",
        {
            "cases": [
                {"name": name, "text": text}
                for name, (text, _) in FAILURE_CASES.items()
            ]
        },
    )
    return {result["name"]: result["message"] for result in results}


@pytest.mark.parametrize("name", FAILURE_CASES)
def test_install_failures_explain_the_actual_adb_reason(
    failure_results: dict, name: str
) -> None:
    assert FAILURE_CASES[name][1] in failure_results[name]


def test_all_deployment_scripts_parse_in_the_real_powershell_parser(
    powershell: str,
) -> None:
    results = _json_result(
        powershell,
        """
$results = foreach ($path in $inputData.paths) {
    $tokens = $null
    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$parseErrors) | Out-Null
    [pscustomobject]@{ path = $path; errors = @($parseErrors | ForEach-Object { $_.Message }) }
}
ConvertTo-Json -InputObject @($results) -Depth 4 -Compress
""",
        {"paths": [str(TOOLS / name) for name in SCRIPTS]},
    )
    assert len(results) == 3
    assert all(not result["errors"] for result in results), results


def test_uncaught_signature_rejection_exits_with_failure(powershell: str) -> None:
    result = _run(
        powershell,
        """
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
$inputData = [Console]::In.ReadToEnd() | ConvertFrom-Json
. $inputData.helpers
Assert-AndroidUpdate $inputData.candidate $inputData.installed 36
""",
        {
            "helpers": str(HELPERS),
            "candidate": _apk(certificate="b"),
            "installed": _apk(),
        },
    )
    assert result.returncode != 0
    assert "SIGNATURE INCOMPATIBLE" in result.stdout + result.stderr


@pytest.mark.parametrize("name", ("Build-Android.ps1", "Install-Android.ps1"))
def test_entry_points_exit_nonzero_on_invalid_arguments_before_running_tools(
    powershell: str, name: str
) -> None:
    result = subprocess.run(
        [
            powershell,
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(TOOLS / name),
            "-Application",
            "InvalidApplication",
        ],
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
        check=False,
    )
    assert result.returncode != 0
    assert "InvalidApplication" in result.stdout + result.stderr


def test_native_command_helper_preserves_failed_exit_code_and_output(
    powershell: str,
) -> None:
    command = base64.b64encode(
        "Write-Output 'native failure detail'; exit 7".encode("utf-16-le")
    ).decode("ascii")
    result = _json_result(
        powershell,
        """
$arguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', $inputData.command)
Invoke-AndroidCommand $inputData.shell $arguments | ConvertTo-Json -Compress
""",
        {"shell": powershell, "command": command},
    )
    assert result["Code"] == 7
    assert "native failure detail" in result["Text"]


def test_file_hash_uses_real_bytes(powershell: str, tmp_path: Path) -> None:
    artifact = tmp_path / "artifact.apk"
    content = b"Pluxy hash probe\x00\xff"
    artifact.write_bytes(content)
    result = _json_result(
        powershell,
        "(Get-FileHash -LiteralPath $inputData.path -Algorithm SHA256).Hash.ToLowerInvariant() | ConvertTo-Json -Compress",
        {"path": str(artifact)},
    )
    assert result == hashlib.sha256(content).hexdigest()


def test_desktop_hash_with_core_module_path(tmp_path: Path) -> None:
    core = shutil.which("pwsh") or shutil.which("pwsh.exe")
    desktop = shutil.which("powershell") or shutil.which("powershell.exe")
    if not core or not desktop:
        pytest.skip("PowerShell 7 and Windows PowerShell 5.1 required for interop")
    artifact = tmp_path / "artifact.apk"
    content = b"Pluxy nested PowerShell hash probe\x00\xff"
    artifact.write_bytes(content)
    child = """
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
$inputData = [Console]::In.ReadToEnd() | ConvertFrom-Json
. $inputData.helpers
(Get-FileHash -LiteralPath $inputData.path -Algorithm SHA256).Hash.ToLowerInvariant() | ConvertTo-Json -Compress
"""
    result = _run(
        core,
        """
$ErrorActionPreference = 'Stop'
$inputData = [Console]::In.ReadToEnd() | ConvertFrom-Json
$env:PSModulePath = (Join-Path $PSHOME 'Modules') + [IO.Path]::PathSeparator + $env:PSModulePath
$inputData.payload | ConvertTo-Json -Compress | & $inputData.desktop -NoLogo -NoProfile -NonInteractive -EncodedCommand $inputData.command
exit $LASTEXITCODE
""",
        {
            "desktop": desktop,
            "command": base64.b64encode(child.encode("utf-16-le")).decode("ascii"),
            "payload": {"helpers": str(HELPERS), "path": str(artifact)},
        },
    )
    assert result.returncode == 0, f"{result.stdout}\n{result.stderr}"
    assert json.loads(result.stdout) == hashlib.sha256(content).hexdigest()


@pytest.fixture(scope="module")
def local_signing_results(
    powershell: str, tmp_path_factory: pytest.TempPathFactory
) -> dict:
    if os.name != "nt":
        pytest.skip("Windows DPAPI required for protected local signing credentials")
    directory = tmp_path_factory.mktemp("protected-signing")
    results = _json_result(
        powershell,
        """
$suffixes = @('STORE_FILE', 'STORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD')
$allNames = foreach ($application in @('PLAYER', 'SERVER')) {
    foreach ($suffix in $suffixes) { "PLUXY_${application}_DEBUG_$suffix" }
}
$path = Join-Path $inputData.directory 'credentials.xml'
$playerStore = Join-Path $inputData.directory 'player.jks'
$serverStore = Join-Path $inputData.directory 'server.jks'
[IO.File]::WriteAllText($playerStore, 'test-keystore-placeholder')
[IO.File]::WriteAllText($serverStore, 'test-keystore-placeholder')
$storeSecret = 'test-store-password-that-must-not-be-printed'
$keySecret = 'test-key-password-that-must-not-be-printed'
$valid = [pscustomobject]@{
    SchemaVersion = 1
    Player = [pscustomobject]@{
        StoreFile = $playerStore; KeyAlias = 'pluxy-player-debug'
        StorePassword = (ConvertTo-SecureString $storeSecret -AsPlainText -Force)
        KeyPassword = (ConvertTo-SecureString $keySecret -AsPlainText -Force)
    }
    Server = [pscustomobject]@{
        StoreFile = $serverStore; KeyAlias = 'pluxy-server-debug'
        StorePassword = (ConvertTo-SecureString $storeSecret -AsPlainText -Force)
        KeyPassword = (ConvertTo-SecureString $keySecret -AsPlainText -Force)
    }
}
$results = foreach ($case in @('protected_both', 'player_only', 'missing_config', 'explicit', 'partial', 'corrupt', 'plaintext', 'missing_keystore', 'mixed')) {
    foreach ($name in $allNames) { [Environment]::SetEnvironmentVariable($name, $null) }
    $valid | Export-Clixml -LiteralPath $path
    $modules = @('app', 'serverapp')
    $configPath = $path
    if ($case -eq 'player_only') { $modules = @('app') }
    if ($case -eq 'missing_config') { $configPath = Join-Path $inputData.directory 'absent.xml' }
    if ($case -eq 'corrupt') { [IO.File]::WriteAllText($path, '<unreadable>') }
    if ($case -eq 'explicit' -or $case -eq 'mixed') {
        foreach ($suffix in $suffixes) { [Environment]::SetEnvironmentVariable("PLUXY_PLAYER_DEBUG_$suffix", "explicit-$suffix") }
        if ($case -eq 'explicit') {
            $modules = @('app')
            [IO.File]::WriteAllText($path, '<unreadable>')
        }
    }
    if ($case -eq 'partial') { [Environment]::SetEnvironmentVariable('PLUXY_PLAYER_DEBUG_KEY_ALIAS', 'explicit-partial-alias') }
    if ($case -eq 'plaintext') {
        $unprotected = [pscustomobject]@{
            SchemaVersion = 1
            Player = [pscustomobject]@{ StoreFile = $playerStore; KeyAlias = 'pluxy-player-debug'; StorePassword = $storeSecret; KeyPassword = $keySecret }
            Server = $valid.Server
        }
        $unprotected | Export-Clixml -LiteralPath $path
    }
    if ($case -eq 'missing_keystore') {
        $missing = [pscustomobject]@{
            SchemaVersion = 1
            Player = $valid.Player
            Server = [pscustomobject]@{ StoreFile = (Join-Path $inputData.directory 'absent.jks'); KeyAlias = 'pluxy-server-debug'; StorePassword = $valid.Server.StorePassword; KeyPassword = $valid.Server.KeyPassword }
        }
        $missing | Export-Clixml -LiteralPath $path
    }
    $initialized = @()
    $message = $null
    try { $initialized = @(Initialize-AndroidDebugSigning -Modules $modules -ConfigurationPath $configPath) }
    catch { $message = $_.Exception.Message }
    $setNames = @($allNames | Where-Object { [Environment]::GetEnvironmentVariable($_) })
    $playerLoaded = [Environment]::GetEnvironmentVariable('PLUXY_PLAYER_DEBUG_STORE_PASSWORD') -ceq $storeSecret -and [Environment]::GetEnvironmentVariable('PLUXY_PLAYER_DEBUG_KEY_PASSWORD') -ceq $keySecret
    $serverLoaded = [Environment]::GetEnvironmentVariable('PLUXY_SERVER_DEBUG_STORE_PASSWORD') -ceq $storeSecret -and [Environment]::GetEnvironmentVariable('PLUXY_SERVER_DEBUG_KEY_PASSWORD') -ceq $keySecret
    $explicitPreserved = [Environment]::GetEnvironmentVariable('PLUXY_PLAYER_DEBUG_KEY_ALIAS') -ceq 'explicit-KEY_ALIAS'
    $partialPreserved = [Environment]::GetEnvironmentVariable('PLUXY_PLAYER_DEBUG_KEY_ALIAS') -ceq 'explicit-partial-alias'
    $encrypted = -not ([IO.File]::ReadAllText($path).Contains($storeSecret) -or [IO.File]::ReadAllText($path).Contains($keySecret))
    foreach ($name in $initialized) { [Environment]::SetEnvironmentVariable($name, $null) }
    $afterCleanup = @($allNames | Where-Object { [Environment]::GetEnvironmentVariable($_) })
    [pscustomobject]@{
        case = $case; initialized = @($initialized); setNames = @($setNames); afterCleanup = @($afterCleanup)
        playerLoaded = $playerLoaded; serverLoaded = $serverLoaded; explicitPreserved = $explicitPreserved
        partialPreserved = $partialPreserved; encrypted = $encrypted; message = $message
    }
}
ConvertTo-Json -InputObject @($results) -Depth 4 -Compress
""",
        {"directory": str(directory)},
    )
    return {result["case"]: result for result in results}


@pytest.mark.parametrize("case", ("protected_both", "player_only"))
def test_protected_credentials_load_only_requested_apps_and_can_be_cleared(
    local_signing_results: dict, case: str
) -> None:
    result = local_signing_results[case]
    assert result["message"] is None
    assert result["encrypted"]
    assert result["playerLoaded"]
    assert result["serverLoaded"] is (case == "protected_both")
    assert len(result["initialized"]) == (8 if case == "protected_both" else 4)
    assert result["afterCleanup"] == []


@pytest.mark.parametrize("case", ("missing_config", "explicit", "mixed"))
def test_local_signing_preserves_explicit_configuration_and_optional_fallback(
    local_signing_results: dict, case: str
) -> None:
    result = local_signing_results[case]
    assert result["message"] is None
    assert result["explicitPreserved"] is (case != "missing_config")
    assert result["playerLoaded"] is False
    assert result["serverLoaded"] is (case == "mixed")
    assert len(result["initialized"]) == (4 if case == "mixed" else 0)
    assert len(result["afterCleanup"]) == (0 if case == "missing_config" else 4)


@pytest.mark.parametrize(
    "case", ("partial", "corrupt", "plaintext", "missing_keystore")
)
def test_invalid_local_signing_never_partially_sets_variables_or_exposes_secrets(
    local_signing_results: dict, case: str
) -> None:
    result = local_signing_results[case]
    assert result["message"] is not None
    assert "test-store-password" not in result["message"]
    assert "test-key-password" not in result["message"]
    assert result["initialized"] == []
    assert result["playerLoaded"] is False
    assert result["serverLoaded"] is False
    if case == "partial":
        assert result["partialPreserved"]
        assert result["afterCleanup"] == ["PLUXY_PLAYER_DEBUG_KEY_ALIAS"]
    else:
        assert result["afterCleanup"] == []
