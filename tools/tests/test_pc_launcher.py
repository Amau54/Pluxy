"""Exercise the Windows PC bootstrap without installing Python or opening a UI."""

from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

import pytest


ROOT = Path(__file__).resolve().parents[2]
HELPERS = ROOT / "tools/PC-Launcher.ps1"
ENTRY = ROOT / "tools/Start-PC.ps1"


def _shells() -> list[str | None]:
    found = []
    for names in (("pwsh", "pwsh.exe"), ("powershell", "powershell.exe")):
        executable = next(
            (path for name in names if (path := shutil.which(name))), None
        )
        if executable and executable not in found:
            found.append(executable)
    return found or [None]


@pytest.fixture(scope="module", params=_shells())
def powershell(request: pytest.FixtureRequest) -> str:
    if os.name != "nt" or request.param is None:
        pytest.skip("Windows PowerShell required for the PC bootstrap integration")
    return request.param


def _run(shell: str, code: str, payload: dict) -> subprocess.CompletedProcess[str]:
    encoded = base64.b64encode(code.encode("utf-16-le")).decode("ascii")
    return subprocess.run(
        [shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
         "Bypass", "-EncodedCommand", encoded],
        input=json.dumps(payload), capture_output=True, encoding="utf-8",
        errors="replace", timeout=45, check=False,
    )


def _result(shell: str, body: str, payload: dict | None = None) -> dict | list:
    result = _run(
        shell,
        """
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
$inputData = [Console]::In.ReadToEnd() | ConvertFrom-Json
. $inputData.helpers
""" + body,
        {"helpers": str(HELPERS), **(payload or {})},
    )
    assert result.returncode == 0, f"{result.stdout}\n{result.stderr}"
    # Installer progress uses Write-Host; the final line is the structured probe.
    return json.loads(result.stdout.splitlines()[-1])


def test_scripts_parse_without_running_the_bootstrap(powershell: str) -> None:
    result = _result(
        powershell,
        """
$results = foreach ($path in $inputData.paths) {
    $tokens = $null
    $errors = $null
    [Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors) | Out-Null
    [pscustomobject]@{ path = $path; errors = @($errors | ForEach-Object { $_.Message }) }
}
ConvertTo-Json -InputObject @($results) -Depth 4 -Compress
""",
        {"paths": [str(HELPERS), str(ENTRY)]},
    )
    assert len(result) == 2
    assert all(not item["errors"] for item in result), result


def test_native_process_keeps_arguments_and_failure_code(powershell: str) -> None:
    child = base64.b64encode(
        "[Console]::Out.Write('native failure details'); exit 17".encode("utf-16-le")
    ).decode("ascii")
    result = _result(
        powershell,
        """
Invoke-PCNative -FilePath $inputData.shell -Arguments @('-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', $inputData.child) -TimeoutSeconds 10 | ConvertTo-Json -Compress
""",
        {"shell": powershell, "child": child},
    )
    assert result["Code"] == 17
    assert "native failure details" in result["Output"]


def test_native_process_preserves_literal_windows_arguments(
    powershell: str, tmp_path: Path,
) -> None:
    probe = tmp_path / "literal args & spaces !.ps1"
    probe.write_text(
        "[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)\n"
        "ConvertTo-Json -InputObject @($args) -Compress\n",
        encoding="utf-8",
    )
    arguments = ["folder with spaces", "& (literal)!", 'quote"here', "C:\\folder space\\"]
    result = _result(
        powershell,
        """
$arguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $inputData.probe) + @($inputData.arguments)
Invoke-PCNative -FilePath $inputData.shell -Arguments $arguments -TimeoutSeconds 10 | ConvertTo-Json -Compress
""",
        {"shell": powershell, "probe": str(probe), "arguments": arguments},
    )
    assert result["Code"] == 0, result
    assert json.loads(result["Output"]) == arguments


def test_native_timeout_terminates_the_spawned_process_tree(
    powershell: str, tmp_path: Path,
) -> None:
    parent = tmp_path / "timeout parent.ps1"
    record = tmp_path / "owned-processes.json"
    parent.write_text(
        """
param([string]$Shell, [string]$Record)
$command = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('Start-Sleep -Seconds 30'))
$child = Start-Process -FilePath $Shell -ArgumentList @('-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', $command) -WindowStyle Hidden -PassThru
$child.Refresh()
[pscustomobject]@{ parent = $PID; child = $child.Id; childTicks = $child.StartTime.ToUniversalTime().Ticks } | ConvertTo-Json -Compress | Set-Content -LiteralPath $Record -Encoding UTF8
Start-Sleep -Seconds 30
""",
        encoding="utf-8",
    )
    result = _result(
        powershell,
        """
$timedOut = $false
$childAlive = $false
$parentAlive = $false
try {
    $arguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $inputData.parent, '-Shell', $inputData.shell, '-Record', $inputData.record)
    Invoke-PCNative -FilePath $inputData.shell -Arguments $arguments -TimeoutSeconds 3 | Out-Null
} catch { $timedOut = $_.Exception.Message -match '3' }
if (Test-Path -LiteralPath $inputData.record) {
    $owned = Get-Content -LiteralPath $inputData.record -Raw | ConvertFrom-Json
    $parentAlive = $null -ne (Get-Process -Id $owned.parent -ErrorAction SilentlyContinue)
    $child = Get-Process -Id $owned.child -ErrorAction SilentlyContinue
    if ($null -ne $child -and $child.StartTime.ToUniversalTime().Ticks -eq $owned.childTicks) {
        $childAlive = $true
        # Clean up only the exact child created by this temporary fixture.
        $child.Kill()
        $child.WaitForExit(5000) | Out-Null
    }
}
[pscustomobject]@{ timedOut = $timedOut; parentAlive = $parentAlive; childAlive = $childAlive; recorded = (Test-Path -LiteralPath $inputData.record) } | ConvertTo-Json -Compress
""",
        {"shell": powershell, "parent": str(parent), "record": str(record)},
    )
    assert result["recorded"], "The temporary parent must have spawned its child"
    assert result["timedOut"]
    assert not result["parentAlive"]
    assert not result["childAlive"]


@pytest.fixture(scope="module")
def python_probe_results(
    powershell: str, tmp_path_factory: pytest.TempPathFactory,
) -> dict:
    directory = tmp_path_factory.mktemp("Python probes & spaces !")
    result = _result(
        powershell,
        r"""
$results = foreach ($case in @('python311', 'python314', 'too_old', 'future_python', 'python2', 'prerelease', 'store_alias', 'native_failure', 'invalid_json', 'python32')) {
    $parent = Join-Path $inputData.directory $case
    if ($case -eq 'store_alias') { $parent = Join-Path $parent 'Microsoft\WindowsApps' }
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $executable = Join-Path $parent 'python.exe'
    [IO.File]::WriteAllText($executable, 'probe fixture placeholder')
    $script:probeCase = $case
    $script:probeCalls = 0
    function Invoke-PCNative {
        param([string]$FilePath, [string[]]$Arguments, [int]$TimeoutSeconds)
        $script:probeCalls++
        if ($script:probeCase -eq 'native_failure') { return [pscustomobject]@{ Code = 1; Output = ''; Error = 'not Python' } }
        if ($script:probeCase -eq 'invalid_json') { return [pscustomobject]@{ Code = 0; Output = 'not a Python version'; Error = '' } }
        $major = 3
        $minor = 12
        $release = 'final'
        $bits = 64
        if ($script:probeCase -eq 'python311') { $minor = 11 }
        if ($script:probeCase -eq 'python314') { $minor = 14 }
        if ($script:probeCase -eq 'too_old') { $minor = 10 }
        if ($script:probeCase -eq 'future_python') { $minor = 15 }
        if ($script:probeCase -eq 'python2') { $major = 2; $minor = 7 }
        if ($script:probeCase -eq 'prerelease') { $release = 'candidate' }
        if ($script:probeCase -eq 'python32') { $bits = 32 }
        $output = [pscustomobject]@{ Executable = $FilePath; Major = $major; Minor = $minor; Micro = 0; ReleaseLevel = $release; Bits = $bits } | ConvertTo-Json -Compress
        return [pscustomobject]@{ Code = 0; Output = $output; Error = '' }
    }
    $info = Get-PCPythonInfo -Executable $executable
    [pscustomobject]@{ case = $case; accepted = $null -ne $info; info = $info; calls = $script:probeCalls; os64 = [Environment]::Is64BitOperatingSystem }
}
ConvertTo-Json -InputObject @($results) -Depth 5 -Compress
""",
        {"directory": str(directory)},
    )
    return {item["case"]: item for item in result}


@pytest.mark.parametrize("case", ("python311", "python314"))
def test_only_compatible_stable_python_is_reused(python_probe_results: dict, case: str) -> None:
    assert python_probe_results[case]["accepted"]


@pytest.mark.parametrize(
    "case", ("too_old", "future_python", "python2", "prerelease", "store_alias", "native_failure", "invalid_json"),
)
def test_incompatible_python_and_store_aliases_are_not_selected(
    python_probe_results: dict, case: str,
) -> None:
    result = python_probe_results[case]
    assert not result["accepted"]
    if case == "store_alias":
        assert result["calls"] == 0


def test_32bit_python_is_not_reused_on_64bit_windows(python_probe_results: dict) -> None:
    result = python_probe_results["python32"]
    if not result["os64"]:
        pytest.skip("64-bit Windows required for interpreter architecture mismatch")
    assert not result["accepted"]


@pytest.mark.parametrize("architecture", ("x64", "arm64", "x86"))
def test_python_installer_is_pinned_to_an_official_https_asset(
    powershell: str, architecture: str,
) -> None:
    result = _result(
        powershell,
        "Get-PCPythonInstaller -Architecture $inputData.architecture | ConvertTo-Json -Compress",
        {"architecture": architecture},
    )
    assert result["Architecture"] == architecture
    assert re.fullmatch(r"3\.14\.\d+", result["Version"])
    assert result["Url"].startswith(f"https://www.python.org/ftp/python/{result['Version']}/python-")
    assert result["Url"].endswith(".exe")
    assert re.fullmatch(r"[a-fA-F0-9]{64}", result["Sha256"])


@pytest.fixture(scope="module")
def installer_results(
    powershell: str, tmp_path_factory: pytest.TempPathFactory,
) -> dict:
    directory = tmp_path_factory.mktemp("PC installer & spaces !")
    result = _result(
        powershell,
        """
$results = foreach ($case in @('success', 'restart_required', 'download_failure', 'signature_failure', 'installer_failure', 'missing_python')) {
    $installDirectory = Join-Path $inputData.directory $case
    $script:installerCase = $case
    $script:installerDownloads = 0
    $script:installerValidations = 0
    $script:installerExecutions = 0
    $script:installerArgs = @()
    function Save-PCPythonInstaller {
        param($Installer, [string]$Destination)
        $script:installerDownloads++
        if ($script:installerCase -eq 'download_failure') { throw 'download interrupted' }
        [IO.File]::WriteAllText($Destination, 'fixture installer only')
    }
    function Assert-PCPythonInstaller {
        param([string]$Path, [string]$Sha256)
        $script:installerValidations++
        if ($script:installerCase -eq 'signature_failure') { throw 'installer signature rejected' }
    }
    function Invoke-PCNative {
        param([string]$FilePath, [string[]]$Arguments, [int]$TimeoutSeconds)
        $script:installerExecutions++
        $script:installerArgs = @($Arguments)
        $code = 0
        if ($script:installerCase -eq 'installer_failure') { $code = 1603 }
        if ($script:installerCase -eq 'restart_required') { $code = 3010 }
        return [pscustomobject]@{ Code = $code; Output = 'fixture installer'; Error = '' }
    }
    function Get-PCPythonInfo {
        param([string]$Executable)
        if ($script:installerExecutions -eq 0) { return $null }
        if ($script:installerCase -eq 'missing_python') { return $null }
        return [pscustomobject]@{ Executable = $Executable; Major = 3; Minor = 14; Micro = 8 }
    }
    $path = $null
    $message = $null
    try { $path = Install-PCPython -InstallDirectory $installDirectory }
    catch { $message = $_.Exception.Message }
    [pscustomobject]@{
        case = $case; path = $path; error = $message
        downloads = $script:installerDownloads; validations = $script:installerValidations
        executions = $script:installerExecutions; args = @($script:installerArgs)
    }
}
ConvertTo-Json -InputObject @($results) -Depth 5 -Compress
""",
        {"directory": str(directory)},
    )
    return {item["case"]: item for item in result}


@pytest.mark.parametrize("case", ("success", "restart_required"))
def test_installer_accepts_success_codes_and_installs_without_admin_or_path_changes(
    installer_results: dict, case: str,
) -> None:
    result = installer_results[case]
    assert result["error"] is None
    assert result["path"].endswith("python.exe")
    assert result["downloads"] == result["validations"] == result["executions"] == 1
    assert "InstallAllUsers=0" in result["args"]
    assert "PrependPath=0" in result["args"]


@pytest.mark.parametrize("case", ("download_failure", "signature_failure"))
def test_failed_download_or_signature_never_executes_the_installer(
    installer_results: dict, case: str,
) -> None:
    result = installer_results[case]
    assert result["error"]
    assert result["path"] is None
    assert result["executions"] == 0


@pytest.mark.parametrize("case", ("installer_failure", "missing_python"))
def test_failed_or_incomplete_python_installation_never_returns_a_runtime(
    installer_results: dict, case: str,
) -> None:
    result = installer_results[case]
    assert result["error"]
    assert result["path"] is None


@pytest.fixture(scope="module")
def environment_results(
    powershell: str, tmp_path_factory: pytest.TempPathFactory,
) -> dict:
    directory = tmp_path_factory.mktemp("PC environment & spaces !")
    result = _result(
        powershell,
        r"""
$results = foreach ($case in @('missing_python', 'compatible_python', 'ready_venv', 'incomplete_venv', 'pip_failure', 'venv_failure', 'installation_failure')) {
    $server = Join-Path $inputData.directory $case
    $runtime = Join-Path $server 'runtime'
    $venvPython = Join-Path $server '.venv\Scripts\python.exe'
    $systemPython = Join-Path $server 'Existing Python\python.exe'
    New-Item -ItemType Directory -Path $server -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $server 'requirements.txt'), 'test-dependency==1.0')
    [IO.File]::WriteAllText((Join-Path $server 'config.json'), '{"keep":"user settings"}')
    $script:pcCase = $case
    $script:pcReady = $case -eq 'ready_venv'
    $script:pcInstalls = 0
    $script:pcCreates = 0
    $script:pcPip = 0
    $script:pcNativeCalls = @()
    if ($case -eq 'compatible_python') {
        New-Item -ItemType Directory -Path (Split-Path $systemPython -Parent) -Force | Out-Null
        [IO.File]::WriteAllText($systemPython, 'test python placeholder')
    }
    if ($case -in @('ready_venv', 'incomplete_venv', 'pip_failure')) {
        New-Item -ItemType Directory -Path (Split-Path $venvPython -Parent) -Force | Out-Null
        [IO.File]::WriteAllText($venvPython, 'test python placeholder')
    }
    function Get-PCPythonCandidates { if ($script:pcCase -eq 'compatible_python') { return @($systemPython) }; return @() }
    function Get-PCPythonInfo {
        param([string]$Executable)
        if (Test-Path -LiteralPath $Executable -PathType Leaf) {
            return [pscustomobject]@{ Executable = $Executable; Major = 3; Minor = 12; Micro = 10 }
        }
        return $null
    }
    function Install-PCPython {
        param([string]$InstallDirectory)
        $script:pcInstalls++
        if ($script:pcCase -eq 'installation_failure') { throw 'test installer failed' }
        New-Item -ItemType Directory -Path $InstallDirectory -Force | Out-Null
        $installed = Join-Path $InstallDirectory 'python.exe'
        [IO.File]::WriteAllText($installed, 'test python placeholder')
        return $installed
    }
    function Test-PCDependencies {
        param([string]$Python, [string]$Requirements)
        return $script:pcReady
    }
    function Invoke-PCNative {
        param([string]$FilePath, [string[]]$Arguments, [int]$TimeoutSeconds)
        $script:pcNativeCalls += [pscustomobject]@{ file = $FilePath; args = @($Arguments) }
        if ($Arguments -contains 'venv') {
            $script:pcCreates++
            if ($script:pcCase -eq 'venv_failure') { return [pscustomobject]@{ Code = 7; Output = 'venv refused'; Error = '' } }
            New-Item -ItemType Directory -Path (Split-Path $venvPython -Parent) -Force | Out-Null
            [IO.File]::WriteAllText($venvPython, 'test python placeholder')
        }
        if (($Arguments -contains 'pip') -and ($Arguments -contains 'install')) {
            $script:pcPip++
            if ($script:pcCase -eq 'pip_failure') { return [pscustomobject]@{ Code = 8; Output = 'dependency download interrupted'; Error = '' } }
            $script:pcReady = $true
        }
        return [pscustomobject]@{ Code = 0; Output = ''; Error = '' }
    }
    $errorText = $null
    $first = $null
    $second = $null
    try {
        $first = Initialize-PCEnvironment -ServerDirectory $server -RuntimeDirectory $runtime
        $second = Initialize-PCEnvironment -ServerDirectory $server -RuntimeDirectory $runtime
    } catch { $errorText = $_.Exception.Message }
    [pscustomobject]@{
        case = $case; first = $first; second = $second; expectedPython = $venvPython
        installs = $script:pcInstalls; creates = $script:pcCreates; pip = $script:pcPip
        ready = $script:pcReady; error = $errorText; calls = @($script:pcNativeCalls)
        config = [IO.File]::ReadAllText((Join-Path $server 'config.json'))
    }
}
ConvertTo-Json -InputObject @($results) -Depth 8 -Compress
""",
        {"directory": str(directory)},
    )
    return {item["case"]: item for item in result}


@pytest.mark.parametrize("case", ("missing_python", "compatible_python"))
def test_first_run_installs_only_when_python_is_missing_and_reuses_next_run(
    environment_results: dict, case: str,
) -> None:
    result = environment_results[case]
    assert result["error"] is None
    assert result["first"] == result["second"] == result["expectedPython"]
    assert result["installs"] == (1 if case == "missing_python" else 0)
    assert result["creates"] == 1
    assert result["pip"] >= 1
    assert result["ready"]
    assert json.loads(result["config"]) == {"keep": "user settings"}


def test_healthy_venv_needs_no_download_or_dependency_install(
    environment_results: dict,
) -> None:
    result = environment_results["ready_venv"]
    assert result["error"] is None
    assert result["installs"] == result["creates"] == result["pip"] == 0
    assert result["first"] == result["second"] == result["expectedPython"]


def test_incomplete_existing_venv_repairs_dependencies_without_reinstalling_python(
    environment_results: dict,
) -> None:
    result = environment_results["incomplete_venv"]
    assert result["error"] is None
    assert result["installs"] == result["creates"] == 0
    assert result["pip"] >= 1
    assert result["ready"]
    assert result["first"] == result["second"] == result["expectedPython"]


@pytest.mark.parametrize("case", ("pip_failure", "venv_failure", "installation_failure"))
def test_preparation_failure_never_returns_a_runnable_environment(
    environment_results: dict, case: str,
) -> None:
    result = environment_results[case]
    assert result["error"]
    assert result["first"] is result["second"] is None
    assert json.loads(result["config"]) == {"keep": "user settings"}


@pytest.mark.parametrize("case", ("current", "wrong_version", "missing_package", "excluded_marker"))
def test_dependency_health_uses_real_python_metadata_and_platform_markers(
    powershell: str, tmp_path: Path, case: str,
) -> None:
    requirements = ROOT / "server/requirements.txt"
    if case != "current":
        requirements = tmp_path / "requirements probe.txt"
        requirements.write_text(
            {
                "wrong_version": "fastapi==0.0.0\n",
                "missing_package": "pluxy-test-package-that-does-not-exist==1.0\n",
                "excluded_marker": 'pluxy-test-package-that-does-not-exist==1.0; sys_platform == "pluxy-test-platform"\n',
            }[case],
            encoding="utf-8",
        )
    result = _result(
        powershell,
        "[pscustomobject]@{ ready = (Test-PCDependencies -Python $inputData.python -Requirements $inputData.requirements) } | ConvertTo-Json -Compress",
        {"python": sys.executable, "requirements": str(requirements)},
    )
    assert result["ready"] is (case in ("current", "excluded_marker"))


@pytest.mark.parametrize("status", ("Valid", "NotSigned", "HashMismatch", "UnknownError"))
def test_installer_requires_a_valid_python_foundation_signature(
    powershell: str, tmp_path: Path, status: str,
) -> None:
    installer = tmp_path / "Python installer & spaces !.exe"
    content = b"fixture installer, never executable"
    installer.write_bytes(content)
    result = _result(
        powershell,
        """
function Get-AuthenticodeSignature {
    param([string]$FilePath, [string]$LiteralPath)
    return [pscustomobject]@{ Status = $inputData.status; SignerCertificate = [pscustomobject]@{ Subject = 'CN=Python Software Foundation, O=Python Software Foundation' } }
}
$accepted = $false
$message = $null
try { Assert-PCPythonInstaller -Path $inputData.path -Sha256 $inputData.sha; $accepted = $true }
catch { $message = $_.Exception.Message }
[pscustomobject]@{ accepted = $accepted; message = $message } | ConvertTo-Json -Compress
""",
        {"path": str(installer), "sha": hashlib.sha256(content).hexdigest(), "status": status},
    )
    assert result["accepted"] is (status == "Valid")
    assert bool(result["message"]) is (status != "Valid")


def test_installer_hash_must_match_pinned_digest(powershell: str, tmp_path: Path) -> None:
    installer = tmp_path / "python.exe"
    installer.write_bytes(b"tampered installer")
    result = _result(
        powershell,
        """
function Get-AuthenticodeSignature {
    param([string]$FilePath, [string]$LiteralPath)
    return [pscustomobject]@{ Status = 'Valid'; SignerCertificate = [pscustomobject]@{ Subject = 'CN=Python Software Foundation, O=Python Software Foundation' } }
}
$accepted = $false
$message = $null
try { Assert-PCPythonInstaller -Path $inputData.path -Sha256 ('a' * 64); $accepted = $true }
catch { $message = $_.Exception.Message }
[pscustomobject]@{ accepted = $accepted; message = $message } | ConvertTo-Json -Compress
""",
        {"path": str(installer)},
    )
    assert not result["accepted"]
    assert result["message"]


def test_valid_signature_from_an_unexpected_publisher_is_rejected(
    powershell: str, tmp_path: Path,
) -> None:
    installer = tmp_path / "foreign installer.exe"
    content = b"another publisher fixture"
    installer.write_bytes(content)
    result = _result(
        powershell,
        """
function Get-AuthenticodeSignature {
    param([string]$FilePath, [string]$LiteralPath)
    return [pscustomobject]@{ Status = 'Valid'; SignerCertificate = [pscustomobject]@{ Subject = 'CN=Other Software Ltd, O=Other Software Ltd' } }
}
$accepted = $false
try { Assert-PCPythonInstaller -Path $inputData.path -Sha256 $inputData.sha; $accepted = $true }
catch { }
[pscustomobject]@{ accepted = $accepted } | ConvertTo-Json -Compress
""",
        {"path": str(installer), "sha": hashlib.sha256(content).hexdigest()},
    )
    assert not result["accepted"]


@pytest.mark.parametrize("scenario", ("success", "failure"))
def test_application_launch_ignores_python_environment_and_restores_it_afterwards(
    powershell: str, tmp_path: Path, scenario: str,
) -> None:
    server = tmp_path / "server & spaces !"
    scripts = server / ".venv/Scripts"
    scripts.mkdir(parents=True)
    (scripts / "python.exe").write_text("fixture only", encoding="utf-8")
    (scripts / "pythonw.exe").write_text("fixture only", encoding="utf-8")
    (server / "pluxy_tray.pyw").write_text("# fixture only\n", encoding="utf-8")
    result = _result(
        powershell,
        """
$env:PYTHONHOME = 'test invalid Python home'
$env:PYTHONPATH = 'test invalid Python path'
$script:observed = $null
function Start-Process {
    param([string]$FilePath, [string[]]$ArgumentList, [string]$WorkingDirectory, [string]$WindowStyle, [string]$ErrorAction)
    $script:observed = [pscustomobject]@{ home = $env:PYTHONHOME; path = $env:PYTHONPATH; arguments = @($ArgumentList); file = $FilePath; window = $WindowStyle }
    if ($inputData.scenario -eq 'failure') { throw 'fixture launch refused' }
}
$failed = $false
try { Start-PCApplication -Python $inputData.python -ServerDirectory $inputData.server }
catch { $failed = $true }
[pscustomobject]@{ failed = $failed; observed = $script:observed; restoredHome = $env:PYTHONHOME; restoredPath = $env:PYTHONPATH } | ConvertTo-Json -Depth 5 -Compress
""",
        {"python": str(scripts / "python.exe"), "server": str(server), "scenario": scenario},
    )
    assert result["failed"] is (scenario == "failure")
    assert result["observed"]["home"] in (None, "")
    assert result["observed"]["path"] in (None, "")
    assert "-E" in result["observed"]["arguments"]
    assert result["observed"]["file"].endswith("pythonw.exe")
    assert result["observed"]["window"] == "Hidden"
    assert result["restoredHome"] == "test invalid Python home"
    assert result["restoredPath"] == "test invalid Python path"


def _entry_fixture(tmp_path: Path) -> Path:
    directory = tmp_path / "Pluxy dossier été & (spaces) !"
    tools = directory / "tools"
    tools.mkdir(parents=True)
    server = directory / "server"
    server.mkdir()
    (server / "requirements.txt").write_text("", encoding="utf-8")
    (server / "pluxy_tray.pyw").write_text("# entry-point fixture only\n", encoding="utf-8")
    shutil.copyfile(ENTRY, tools / ENTRY.name)
    (tools / "Media-Runtime.ps1").write_text("function Initialize-PCMediaRuntime { return 'media-runtime-fixture' }", encoding="utf-8")
    (tools / HELPERS.name).write_text(
        """
function Initialize-PCEnvironment {
    param([string]$ServerDirectory, [string]$RuntimeDirectory)
    if ($env:PC_TEST_FAIL -eq '1') { throw 'test preparation failed before launch' }
    return (Join-Path $ServerDirectory '.venv\\Scripts\\python.exe')
}
function Start-PCApplication {
    param([string]$Python, [string]$ServerDirectory)
    [IO.File]::WriteAllText($env:PC_TEST_RECORD, ($Python + [Environment]::NewLine + $ServerDirectory))
}
""",
        encoding="utf-8",
    )
    return directory


@pytest.mark.parametrize("scenario", ("prepare_only", "launch", "failure"))
def test_entry_point_only_launches_after_successful_preparation(
    powershell: str, tmp_path: Path, scenario: str,
) -> None:
    directory = _entry_fixture(tmp_path)
    record = tmp_path / "started.txt"
    env = os.environ.copy()
    env.update(PC_TEST_RECORD=str(record), PC_TEST_FAIL="1" if scenario == "failure" else "0")
    result = subprocess.run(
        [powershell, "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
         "Bypass", "-File", str(directory / "tools" / ENTRY.name)]
        + (["-PrepareOnly"] if scenario == "prepare_only" else []),
        capture_output=True, encoding="utf-8", errors="replace", timeout=30,
        check=False, env=env,
    )
    assert (result.returncode == 0) is (scenario != "failure"), result.stdout + result.stderr
    assert record.exists() is (scenario == "launch")
    if scenario == "launch":
        assert str(directory / "server") in record.read_text(encoding="utf-8")
    if scenario == "failure":
        assert "test preparation failed before launch" in result.stdout + result.stderr


def test_batch_double_click_preserves_special_characters_in_install_path(
    tmp_path: Path,
) -> None:
    if os.name != "nt":
        pytest.skip("Windows cmd required for double-click bootstrap")
    directory = _entry_fixture(tmp_path)
    batch = directory / "Pluxy-Server.bat"
    shutil.copyfile(ROOT / batch.name, batch)
    record = tmp_path / "started.txt"
    env = os.environ.copy()
    env.update(PC_TEST_RECORD=str(record), PC_TEST_FAIL="0")
    result = subprocess.run(
        f'cmd.exe /d /c call "{batch}"',
        capture_output=True, encoding="utf-8", errors="replace", timeout=30,
        check=False, env=env,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert record.exists(), result.stdout + result.stderr
    assert str(directory / "server") in record.read_text(encoding="utf-8")


def test_pc_changes_have_their_own_windows_gate_without_android_rebuild() -> None:
    workflow = (ROOT / ".github/workflows/pc-windows.yml").read_text(encoding="utf-8")
    android = (ROOT / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
    assert "runs-on: windows-latest" in workflow
    assert "python -m pytest -q server/tests tools/tests" in workflow
    assert "contents: read" in workflow
    assert "persist-credentials: false" in workflow
    assert "Pluxy-Server.bat" in workflow
    assert "Initialize-PCEnvironment" in workflow
    assert "Test-PCDependencies" in workflow
    assert "Get-PCPythonCandidates { return @() }" in workflow
    for path in ("tools/PC-Launcher.ps1", "tools/Start-PC.ps1", "tools/tests/test_pc_launcher.py"):
        assert f"'{path}'" in workflow
        assert android.count(f"'!{path}'") == 2
    for action in re.findall(r"uses: ([^\s#]+)", workflow):
        assert re.fullmatch(r"[\w/-]+@[a-f0-9]{40}", action), action
