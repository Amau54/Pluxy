# PC bootstrap helpers, compatible with Windows PowerShell 5.1 and PowerShell 7.
if ($PSVersionTable.PSVersion.Major -lt 6) {
    # A PowerShell Core parent can otherwise shadow built-in Desktop modules.
    Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Force -ErrorAction Stop
    Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -Force -ErrorAction Stop
}
Set-StrictMode -Version Latest

function ConvertTo-PCNativeArgument {
    param([AllowEmptyString()][string]$Argument)
    # Windows CRT escaping; no command interpreter is involved.
    $escaped = [regex]::Replace($Argument, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Invoke-PCNative {
    param([string]$FilePath, [string[]]$Arguments, [ValidateRange(1, 1800)][int]$TimeoutSeconds = 120)
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $FilePath
    $info.Arguments = (@($Arguments | ForEach-Object { ConvertTo-PCNativeArgument $_ }) -join ' ')
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardInput = $true
    $info.StandardOutputEncoding = New-Object Text.UTF8Encoding($false)
    $info.StandardErrorEncoding = New-Object Text.UTF8Encoding($false)
    $info.EnvironmentVariables.Remove('PYTHONHOME')
    $info.EnvironmentVariables.Remove('PYTHONPATH')
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw 'Impossible de demarrer le programme.' }
        $process.StandardInput.Close()
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            if ($PSVersionTable.PSVersion.Major -ge 7) { $process.Kill($true) }
            else {
                # This independent timeout cannot recursively spawn more taskkills.
                $stopInfo = New-Object Diagnostics.ProcessStartInfo
                $stopInfo.FileName = Join-Path $env:SystemRoot 'System32\taskkill.exe'
                $stopInfo.Arguments = "/PID $($process.Id) /T /F"
                $stopInfo.UseShellExecute = $false
                $stopInfo.CreateNoWindow = $true
                $stopInfo.RedirectStandardOutput = $true
                $stopInfo.RedirectStandardError = $true
                $stopProcess = New-Object Diagnostics.Process
                $stopProcess.StartInfo = $stopInfo
                try {
                    $stopProcess.Start() | Out-Null
                    $stopProcess.StandardOutput.ReadToEndAsync() | Out-Null
                    $stopProcess.StandardError.ReadToEndAsync() | Out-Null
                    if (-not $stopProcess.WaitForExit(15000)) { $stopProcess.Kill() }
                    if (-not $process.HasExited) { $process.Kill() }
                } finally { $stopProcess.Dispose() }
            }
            $process.WaitForExit(5000) | Out-Null
            throw "Delai depasse ($TimeoutSeconds secondes). Relancez Pluxy pour reprendre l'installation."
        }
        if (-not $output.Wait(5000) -or -not $errors.Wait(5000)) { throw 'Le programme a termine sans fermer sa sortie.' }
        return [pscustomobject]@{ Code = $process.ExitCode; Output = $output.Result; Error = $errors.Result }
    } finally { $process.Dispose() }
}

function Get-PCPythonInfo {
    param([string]$Executable)
    if ([string]::IsNullOrWhiteSpace($Executable) -or
        $Executable -match '(?i)[\\/]Microsoft[\\/]WindowsApps[\\/]' -or
        -not (Test-Path -LiteralPath $Executable -PathType Leaf)) { return $null }
    $probe = @'
import json, struct, sys, venv, ensurepip
v = sys.version_info
print(json.dumps(dict(Executable=sys.executable, Major=v.major, Minor=v.minor, Micro=v.micro, ReleaseLevel=v.releaselevel, Bits=struct.calcsize("P") * 8)))
'@
    try {
        $result = Invoke-PCNative -FilePath $Executable -Arguments @('-I', '-c', $probe) -TimeoutSeconds 15
        if ($result.Code -ne 0) { return $null }
        $python = $result.Output.Trim() | ConvertFrom-Json
        if ($python.Major -ne 3 -or $python.Minor -lt 11 -or $python.Minor -gt 14) { return $null }
        if ($python.PSObject.Properties['ReleaseLevel'] -and $python.ReleaseLevel -ne 'final') { return $null }
        # A 32-bit interpreter on 64-bit Windows cannot use our AMD64 wheels.
        if ($python.PSObject.Properties['Bits'] -and $python.Bits -eq 32 -and [Environment]::Is64BitOperatingSystem) { return $null }
        return $python
    } catch { return $null }
}

function Get-PCPythonCandidates {
    $candidates = New-Object 'Collections.Generic.List[string]'
    foreach ($name in @('python.exe', 'python3.exe')) {
        foreach ($command in @(Get-Command $name -CommandType Application -All -ErrorAction SilentlyContinue)) { $candidates.Add($command.Source) }
    }
    # Registered interpreters remain discoverable without PATH entries.
    foreach ($hive in @([Microsoft.Win32.RegistryHive]::CurrentUser, [Microsoft.Win32.RegistryHive]::LocalMachine)) {
        foreach ($view in @([Microsoft.Win32.RegistryView]::Registry64, [Microsoft.Win32.RegistryView]::Registry32)) {
            $registry = $null
            $core = $null
            try {
                $registry = [Microsoft.Win32.RegistryKey]::OpenBaseKey($hive, $view)
                $core = $registry.OpenSubKey('Software\Python\PythonCore')
                if ($null -eq $core) { continue }
                foreach ($version in $core.GetSubKeyNames()) {
                    $key = $core.OpenSubKey("$version\InstallPath")
                    if ($null -eq $key) { continue }
                    try {
                        $executable = [string]$key.GetValue('ExecutablePath', '')
                        $directory = [string]$key.GetValue('', '')
                        if ($executable) { $candidates.Add($executable) }
                        elseif ($directory) { $candidates.Add((Join-Path $directory 'python.exe')) }
                    } finally { $key.Dispose() }
                }
            } catch { Write-Verbose 'Enregistrement Python inaccessible, recherche suivante.' }
            finally {
                if ($null -ne $core) { $core.Dispose() }
                if ($null -ne $registry) { $registry.Dispose() }
            }
        }
    }
    if ($env:LOCALAPPDATA) {
        foreach ($minor in @(14, 13, 12, 11)) { $candidates.Add((Join-Path $env:LOCALAPPDATA "Programs\Python\Python3$minor\python.exe")) }
    }
    return @($candidates | Select-Object -Unique)
}

function Get-PCPythonInstaller {
    param([string]$Architecture)
    if (-not $Architecture) {
        $Architecture = $env:PROCESSOR_ARCHITEW6432
        if (-not $Architecture) { $Architecture = $env:PROCESSOR_ARCHITECTURE }
    }
    # Official digests: https://www.python.org/downloads/release/python-3148/
    switch ($Architecture.ToLowerInvariant()) {
        { $_ -in @('amd64', 'x64') } {
            $suffix = '-amd64'; $arch = 'x64'
            $sha = '759be887b96e736a3ca886daf8d575f18fcae1a09efab6902f42d59e8999f8ef'
        }
        'arm64' {
            $suffix = '-arm64'; $arch = 'arm64'
            $sha = '53ba74b5b4370eb823fe0005eedeab907479f7339cecb11efea0e75ab5f10cfc'
        }
        { $_ -in @('x86', 'i386') } {
            $suffix = ''; $arch = 'x86'
            $sha = '963def30c7ebc6381a1ee9056d251ddc4520b18391da2cf0602cfb58f5b80568'
        }
        default { throw "Architecture Windows non prise en charge : $Architecture" }
    }
    return [pscustomobject]@{ Version = '3.14.8'; Architecture = $arch; Url = "https://www.python.org/ftp/python/3.14.8/python-3.14.8$suffix.exe"; Sha256 = $sha }
}

function Save-PCPythonInstaller {
    param($Installer, [string]$Destination)
    if ($Installer.Url -notmatch '^https://www\.python\.org/ftp/python/') { throw 'Adresse Python officielle requise.' }
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        try {
            Invoke-WebRequest -Uri $Installer.Url -OutFile $Destination -UseBasicParsing -TimeoutSec 180 -ErrorAction Stop
            return
        } catch {
            if ($attempt -eq 2) { throw 'Telechargement de Python impossible. Verifiez la connexion Internet, puis relancez Pluxy.' }
            Start-Sleep -Seconds 2
        }
    }
}

function Assert-PCPythonInstaller {
    param([string]$Path, [string]$Sha256)
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Sha256) { throw 'Le fichier Python telecharge est incomplet ou modifie. Installation refusee.' }
    $signature = Get-AuthenticodeSignature -LiteralPath $Path
    if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate -or
        $signature.SignerCertificate.Subject -notmatch '(?i)(^|,\s*)(CN|O)=Python Software Foundation(,|$)') {
        throw "La signature de l'installateur Python est invalide. Installation refusee."
    }
}

function Install-PCPython {
    param([string]$InstallDirectory)
    $existing = Join-Path $InstallDirectory 'python.exe'
    if ($null -ne (Get-PCPythonInfo -Executable $existing)) { return $existing }
    $installer = Get-PCPythonInstaller
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ('Pluxy-Python-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temporary -ErrorAction Stop | Out-Null
    $download = Join-Path $temporary 'python-installer.exe'
    try {
        Write-Host "[Pluxy] Python absent ou incompatible. Telechargement de Python $($installer.Version)..."
        Save-PCPythonInstaller -Installer $installer -Destination $download
        Assert-PCPythonInstaller -Path $download -Sha256 $installer.Sha256
        Write-Host '[Pluxy] Installation de Python pour votre compte Windows...'
        $arguments = @('/quiet', 'InstallAllUsers=0', "TargetDir=$InstallDirectory", 'PrependPath=0',
            'Include_launcher=0', 'InstallLauncherAllUsers=0', 'AssociateFiles=0', 'Shortcuts=0',
            'Include_test=0', 'Include_doc=0', 'Include_tools=0', 'Include_tcltk=0',
            'Include_pip=1', 'Include_exe=1', 'Include_lib=1', 'Include_dev=1')
        $result = Invoke-PCNative -FilePath $download -Arguments $arguments -TimeoutSeconds 900
        if ($result.Code -notin @(0, 3010)) { throw "L'installation de Python a echoue (code $($result.Code)). Relancez Pluxy pour reessayer." }
        if ($null -eq (Get-PCPythonInfo -Executable $existing)) { throw 'Python ne fonctionne pas apres installation. Relancez Pluxy pour reessayer.' }
        return $existing
    } finally {
        # Remove only the explicitly created file and its now-empty directory.
        if (Test-Path -LiteralPath $download -PathType Leaf) { Remove-Item -LiteralPath $download -Force -ErrorAction SilentlyContinue }
        if (Test-Path -LiteralPath $temporary -PathType Container) { Remove-Item -LiteralPath $temporary -ErrorAction SilentlyContinue }
    }
}

function Test-PCDependencies {
    param([string]$Python, [string]$Requirements)
    $probe = @'
import importlib, importlib.metadata, pathlib, sys
from pip._vendor.packaging.requirements import Requirement
pending = []
for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    line = line.split("#", 1)[0].strip()
    if not line:
        continue
    requirement = Requirement(line)
    if requirement.marker and not requirement.marker.evaluate():
        continue
    pending.append(requirement)
seen = set()
while pending:
    requirement = pending.pop()
    if not requirement.specifier.contains(importlib.metadata.version(requirement.name), prereleases=False):
        raise RuntimeError("Dependencies need repair")
    key = (requirement.name.lower(), tuple(sorted(requirement.extras)))
    if key in seen:
        continue
    seen.add(key)
    for text in importlib.metadata.requires(requirement.name) or []:
        dependency = Requirement(text)
        if not dependency.marker or any(dependency.marker.evaluate({"extra": extra}) for extra in ("", *requirement.extras)):
            pending.append(dependency)
    if requirement.name.lower() == "uvicorn" and "standard" in requirement.extras:
        for module in ("httptools", "yaml", "watchfiles", "websockets", "dotenv"):
            importlib.import_module(module)
for name in ("fastapi", "uvicorn", "pydantic", "aiofiles", "multipart", "httpx", "pystray", "PIL"):
    importlib.import_module(name)
'@
    try {
        $result = Invoke-PCNative -FilePath $Python -Arguments @('-I', '-c', $probe, $Requirements) -TimeoutSeconds 30
        if ($result.Code -ne 0) { return $false }
        $check = Invoke-PCNative -FilePath $Python -Arguments @('-I', '-m', 'pip', 'check', '--disable-pip-version-check') -TimeoutSeconds 30
        return $check.Code -eq 0
    } catch { return $false }
}

function Initialize-PCEnvironment {
    param([string]$ServerDirectory, [string]$RuntimeDirectory)
    $ServerDirectory = [IO.Path]::GetFullPath($ServerDirectory)
    $requirements = Join-Path $ServerDirectory 'requirements.txt'
    if (-not (Test-Path -LiteralPath $requirements -PathType Leaf)) { throw 'Fichiers Pluxy manquants. Extrayez le ZIP complet avant de lancer Pluxy.' }
    $environment = Join-Path $ServerDirectory '.venv'
    $python = Join-Path $environment 'Scripts\python.exe'
    if ($null -eq (Get-PCPythonInfo -Executable $python)) {
        $basePython = $null
        foreach ($candidate in @((Join-Path $RuntimeDirectory 'python.exe')) + @(Get-PCPythonCandidates)) {
            $compatible = Get-PCPythonInfo -Executable $candidate
            if ($null -ne $compatible) { $basePython = $compatible.Executable; break }
        }
        if (-not $basePython) { $basePython = Install-PCPython -InstallDirectory $RuntimeDirectory }
        if (Test-Path -LiteralPath $environment) {
            $backup = Join-Path $ServerDirectory ('.venv-backup-' + [Guid]::NewGuid().ToString('N'))
            $parent = (Resolve-Path -LiteralPath $ServerDirectory -ErrorAction Stop).ProviderPath.TrimEnd('\', '/')
            $source = (Resolve-Path -LiteralPath $environment -ErrorAction Stop).ProviderPath
            if ([IO.Path]::GetDirectoryName($source) -ne $parent -or [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($backup)) -ne $ServerDirectory.TrimEnd('\', '/')) {
                throw "Chemin de l'environnement Python inattendu. Reparation annulee."
            }
            Move-Item -LiteralPath $environment -Destination $backup -ErrorAction Stop
        }
        Write-Host '[Pluxy] Preparation de votre environnement...'
        $created = Invoke-PCNative -FilePath $basePython -Arguments @('-I', '-m', 'venv', $environment) -TimeoutSeconds 120
        if ($created.Code -ne 0 -or $null -eq (Get-PCPythonInfo -Executable $python)) { throw "Creation de l'environnement Python impossible. $($created.Error) $($created.Output)" }
    }
    if (-not (Test-PCDependencies -Python $python -Requirements $requirements)) {
        Write-Host '[Pluxy] Installation ou reparation des composants Pluxy...'
        $pip = Invoke-PCNative -FilePath $python -Arguments @('-I', '-m', 'ensurepip', '--upgrade') -TimeoutSeconds 120
        if ($pip.Code -ne 0) { throw "Preparation de pip impossible. $($pip.Error) $($pip.Output)" }
        $installed = Invoke-PCNative -FilePath $python -Arguments @('-I', '-m', 'pip', 'install', '--disable-pip-version-check', '--only-binary=:all:', '--retries', '2', '--timeout', '30', '-r', $requirements) -TimeoutSeconds 900
        if ($installed.Code -ne 0) { throw "Installation des composants interrompue. Verifiez Internet et relancez Pluxy. $($installed.Error) $($installed.Output)" }
        if (-not (Test-PCDependencies -Python $python -Requirements $requirements)) { throw 'Les composants Pluxy restent incomplets. Relancez le lanceur pour reessayer.' }
    }
    return $python
}

function Start-PCApplication {
    param([string]$Python, [string]$ServerDirectory)
    $tray = Join-Path $ServerDirectory 'pluxy_tray.pyw'
    if (-not (Test-Path -LiteralPath $tray -PathType Leaf)) { throw 'Application Pluxy manquante. Extrayez le ZIP complet.' }
    $windowless = Join-Path (Split-Path -Parent $Python) 'pythonw.exe'
    if (Test-Path -LiteralPath $windowless -PathType Leaf) { $Python = $windowless }
    # The tray opens the configured port once the server is actually ready.
    $previousHome = $env:PYTHONHOME
    $previousPath = $env:PYTHONPATH
    try {
        # The tray's server subprocess must also inherit a clean Python environment.
        [Environment]::SetEnvironmentVariable('PYTHONHOME', $null, 'Process')
        [Environment]::SetEnvironmentVariable('PYTHONPATH', $null, 'Process')
        Start-Process -FilePath $Python -ArgumentList @('-E', (ConvertTo-PCNativeArgument $tray)) -WorkingDirectory $ServerDirectory -WindowStyle Hidden -ErrorAction Stop | Out-Null
    } finally {
        [Environment]::SetEnvironmentVariable('PYTHONHOME', $previousHome, 'Process')
        [Environment]::SetEnvironmentVariable('PYTHONPATH', $previousPath, 'Process')
    }
}
