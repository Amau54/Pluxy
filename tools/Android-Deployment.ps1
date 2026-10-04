# Shared Windows build/install helpers, compatible with PowerShell 5.1.
if ($PSVersionTable.PSVersion.Major -lt 6) {
    # A pwsh parent can put Core modules first in inherited PSModulePath.
    Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Force -ErrorAction Stop
    Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -Force -ErrorAction Stop
}
Set-StrictMode -Version Latest

function Invoke-AndroidCommand {
    param([string]$Path, [string[]]$Arguments)
    $ErrorActionPreference = 'Continue'
    $lines = @(& $Path @Arguments 2>&1)
    $code = $LASTEXITCODE
    [pscustomobject]@{ Code = $code; Text = ($lines | ForEach-Object { "$_" }) -join "`n" }
}

function Initialize-AndroidDebugSigning {
    param(
        [string[]]$Modules,
        [string]$ConfigurationPath = (Join-Path $env:LOCALAPPDATA 'Pluxy/Signing/debug-2026-10/credentials.xml')
    )
    # Local credentials are protected by Windows DPAPI, outside the repository.
    # Explicit environment configuration always takes precedence, even if partial.
    $pending = @()
    foreach ($module in $Modules) {
        $application = if ($module -eq 'app') { 'Player' } elseif ($module -eq 'serverapp') { 'Server' } else { throw 'Module Android inattendu pour la signature.' }
        $prefix = "PLUXY_$($application.ToUpperInvariant())_DEBUG_"
        $names = @('STORE_FILE', 'STORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD') | ForEach-Object { "$prefix$_" }
        $present = @($names | Where-Object { -not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($_)) })
        if ($present.Count -gt 0) {
            if ($present.Count -ne $names.Count) { throw "Signature $prefix incomplete : configurez les quatre variables ou retirez-les toutes." }
            continue
        }
        $pending += [pscustomobject]@{ Application = $application; Prefix = $prefix }
    }
    if ($pending.Count -eq 0 -or -not (Test-Path -LiteralPath $ConfigurationPath)) { return }
    try {
        $configuration = Import-Clixml -LiteralPath $ConfigurationPath -ErrorAction Stop
        if (-not $configuration.PSObject.Properties['SchemaVersion'] -or $configuration.SchemaVersion -ne 1) { throw 'Unsupported schema' }
        $values = @()
        foreach ($entry in $pending) {
            if (-not $configuration.PSObject.Properties[$entry.Application]) { throw 'Missing application' }
            $credential = $configuration.($entry.Application)
            foreach ($property in @('StoreFile', 'KeyAlias', 'StorePassword', 'KeyPassword')) {
                if (-not $credential.PSObject.Properties[$property]) { throw 'Missing signing field' }
            }
            if (-not [IO.Path]::IsPathRooted($credential.StoreFile) -or -not (Test-Path -LiteralPath $credential.StoreFile -PathType Leaf)) { throw 'Missing keystore' }
            if ([string]::IsNullOrWhiteSpace($credential.KeyAlias)) { throw 'Missing alias' }
            foreach ($property in @('StorePassword', 'KeyPassword')) {
                if ($credential.$property -isnot [Security.SecureString] -or $credential.$property.Length -eq 0) { throw 'Unprotected or empty password' }
            }
            $values += [pscustomobject]@{ Prefix = $entry.Prefix; Credential = $credential }
        }
    } catch { throw 'Configuration de signature locale invalide ou inaccessible. Reenregistrez credentials.xml avec ce compte Windows ; aucun secret affiche.' }

    $initialized = New-Object 'System.Collections.Generic.List[string]'
    try {
        foreach ($entry in $values) {
            foreach ($field in @(@('STORE_FILE', 'StoreFile'), @('KEY_ALIAS', 'KeyAlias'), @('STORE_PASSWORD', 'StorePassword'), @('KEY_PASSWORD', 'KeyPassword'))) {
                $name = "$($entry.Prefix)$($field[0])"
                $value = $entry.Credential.($field[1])
                if ($value -is [Security.SecureString]) {
                    $pointer = [IntPtr]::Zero
                    try {
                        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
                        [Environment]::SetEnvironmentVariable($name, [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer))
                    } finally { if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) } }
                } else { [Environment]::SetEnvironmentVariable($name, [string]$value) }
                $initialized.Add($name)
            }
        }
    } catch {
        foreach ($name in $initialized) { [Environment]::SetEnvironmentVariable($name, $null) }
        throw 'Chargement de la signature locale impossible ; aucun secret affiche.'
    }
    # The build removes only these temporary process variables in its finally block.
    $initialized.ToArray()
}

function Find-AndroidEnvironment {
    param([string]$Repository)
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    $candidates = @($env:JAVA_HOME)
    if ($javaCommand) { $candidates += Split-Path (Split-Path $javaCommand.Source) }
    foreach ($base in @("$env:ProgramFiles/Microsoft", "$env:LOCALAPPDATA/Programs/Microsoft", "$env:ProgramFiles/Eclipse Adoptium", "$env:ProgramFiles/Java")) {
        if (Test-Path -LiteralPath $base) { $candidates += @(Get-ChildItem -LiteralPath $base -Directory | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName) }
    }
    $java = $null
    foreach ($candidate in $candidates) {
        if (-not $candidate -or -not (Test-Path -LiteralPath "$candidate/bin/java.exe")) { continue }
        $version = Invoke-AndroidCommand "$candidate/bin/java.exe" @('-version')
        if ($version.Code -eq 0 -and $version.Text -match 'version "(?:1\.)?(\d+)') {
            if ([int]$Matches[1] -ge 17 -and [int]$Matches[1] -le 23) { $java = $candidate; break }
        }
    }
    if (-not $java) { throw 'JDK compatible introuvable. Installez JDK 17 ou definissez JAVA_HOME.' }
    $env:JAVA_HOME = $java
    $sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $properties = Join-Path $Repository 'client/local.properties'
    if (Test-Path -LiteralPath $properties) {
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { $sdkCandidates += $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    $sdkCandidates += @("$env:LOCALAPPDATA/Android/Sdk", (Join-Path $Repository 'client/.android-codex-verify'))
    $sdk = $sdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath "$_/platform-tools/adb.exe") -and (Test-Path -LiteralPath "$_/build-tools") } | Select-Object -First 1
    if (-not $sdk) { throw 'SDK Android introuvable. Definissez ANDROID_HOME avec platform-tools et build-tools installes.' }
    $sdk = (Resolve-Path -LiteralPath $sdk).Path
    $env:ANDROID_HOME = $sdk; $env:ANDROID_SDK_ROOT = $sdk
    $tools = Get-ChildItem -LiteralPath "$sdk/build-tools" -Directory | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path -LiteralPath "$($_.FullName)/lib/apksigner.jar") -and (Test-Path -LiteralPath "$($_.FullName)/aapt.exe") } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $tools) { throw 'Android build-tools requis pour verifier les APK.' }
    [pscustomobject]@{ Java = "$java/bin/java.exe"; Adb = "$sdk/platform-tools/adb.exe"; Aapt = "$($tools.FullName)/aapt.exe"; Signer = "$($tools.FullName)/lib/apksigner.jar" }
}

function Get-AndroidApkInfo {
    param([string]$Path, $Environment)
    $Path = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $badging = Invoke-AndroidCommand $Environment.Aapt @('dump', 'badging', $Path)
    if ($badging.Code -ne 0 -or $badging.Text -notmatch "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'" ) { throw "APK invalide : $Path" }
    $package = $Matches[1]; $code = [long]$Matches[2]; $name = $Matches[3]
    $minSdk = 1
    if ($badging.Text -match "sdkVersion:'(\d+)'") { $minSdk = [int]$Matches[1] }
    $signing = Invoke-AndroidCommand $Environment.Java @('-jar', $Environment.Signer, 'verify', '--print-certs', $Path)
    if ($signing.Code -ne 0) { throw "Signature APK invalide : $Path`n$($signing.Text)" }
    $certificates = @([regex]::Matches($signing.Text, 'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})') | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } | Sort-Object -Unique)
    if ($certificates.Count -eq 0) { throw "Certificat APK introuvable : $Path" }
    [pscustomobject]@{ Path = $Path; Package = $package; VersionCode = $code; VersionName = $name; MinSdk = $minSdk; Certificates = $certificates; Sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
}

function Assert-AndroidUpdate {
    param($Candidate, $Installed, [int]$DeviceSdk)
    if ($Candidate.MinSdk -gt $DeviceSdk) { throw "Android trop ancien : SDK $DeviceSdk, APK exige SDK $($Candidate.MinSdk)." }
    if (-not $Installed) { return }
    if ($Candidate.Package -ne $Installed.Package) { throw 'Cette APK appartient a une autre application.' }
    if (($Candidate.Certificates -join ',') -ne ($Installed.Certificates -join ',')) { throw "SIGNATURE INCOMPATIBLE : application $($Installed.VersionName), APK $($Candidate.VersionName). Donnees conservees. Utilisez le certificat d'origine ; le script ne desinstalle pas l'application." }
    if ($Candidate.VersionCode -lt $Installed.VersionCode) { throw "VERSION PLUS ANCIENNE : APK $($Candidate.VersionCode), installee $($Installed.VersionCode). Aucune retrogradation effectuee." }
}

function Get-AndroidInstallFailure {
    param([string]$Text)
    switch -Regex ($Text) {
        'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match' { return 'Signature incompatible. Donnees conservees ; retrouvez la cle ou le canal dorigine.' }
        'INSTALL_FAILED_VERSION_DOWNGRADE' { return 'APK plus ancienne que la version installee.' }
        'INSTALL_FAILED_INSUFFICIENT_STORAGE' { return 'Espace de stockage insuffisant sur lappareil.' }
        'INSTALL_FAILED_USER_RESTRICTED' { return 'Installation refusee sur lappareil. Autorisez le debogage et linstallation.' }
        'INSTALL_FAILED_OLDER_SDK' { return 'Version Android trop ancienne pour cette APK.' }
        'unauthorized' { return 'ADB non autorise. Acceptez la demande de debogage sur lappareil.' }
        'offline|device .*not found|no devices' { return 'Appareil deconnecte ou hors ligne. Verifiez USB/Wi-Fi et le port.' }
        default { return "Installation echouee : $Text" }
    }
}
