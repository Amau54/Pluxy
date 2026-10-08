[CmdletBinding()]
param([ValidateSet('Both', 'Player', 'Server')][string]$Application = 'Player', [ValidateSet('Debug', 'Release')][string]$Configuration = 'Debug', [switch]$Verify)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Android-Deployment.ps1"
$repository = Split-Path $PSScriptRoot
$timer = [Diagnostics.Stopwatch]::StartNew()
$localSigningVariables = @()
try {
    $environment = Find-AndroidEnvironment $repository
    $modules = @('app')
    if ($Application -ne 'Player') { Write-Host '[Pluxy] Le lecteur et le serveur sont maintenant dans une seule APK.' }
    if ($Configuration -eq 'Debug') {
        $localSigningVariables = @(Initialize-AndroidDebugSigning -Modules $modules)
        $needsLocalKey = @($modules | Where-Object {
            $prefix = if ($_ -eq 'app') { 'PLUXY_PLAYER_DEBUG_' } else { 'PLUXY_SERVER_DEBUG_' }
            -not [Environment]::GetEnvironmentVariable("${prefix}STORE_FILE")
        }).Count -gt 0
        $localKey = Join-Path $env:USERPROFILE '.android/debug.keystore'
        if ($needsLocalKey -and -not (Test-Path -LiteralPath $localKey)) {
            # Standard local Android debug identity, created once and never rotated.
            [IO.Directory]::CreateDirectory((Split-Path $localKey)) | Out-Null
            $keytool = Join-Path (Split-Path $environment.Java) 'keytool.exe'
            $generated = Invoke-AndroidCommand $keytool @('-genkeypair', '-keystore', $localKey, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Android Debug,O=Android,C=US')
            if ($generated.Code -ne 0) { throw 'Creation de la cle debug locale impossible. Aucune ancienne cle remplacee.' }
        }
    }
    $tasks = @()
    if ($Verify -or $Configuration -eq 'Release') { $tasks += @(':app:testDebugUnitTest', ':serverapp:testDebugUnitTest', ':receiver-core:testDebugUnitTest', ':receiver-dlna:testDebugUnitTest', ':app:lintRelease', ':serverapp:lintRelease', ':receiver-core:lintRelease', ':receiver-dlna:lintRelease', ':receiver-cast:lintRelease') }
    foreach ($module in $modules) {
        if ($Configuration -eq 'Release') {
            $prefix = if ($module -eq 'app') { 'PLUXY_PLAYER_UPLOAD_' } else { 'PLUXY_SERVER_UPLOAD_' }
            foreach ($suffix in @('STORE_FILE', 'STORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD')) { if (-not [Environment]::GetEnvironmentVariable("$prefix$suffix")) { throw "Signature release incomplete : $prefix$suffix manquante. Utilisez Debug pour les tests locaux." } }
        }
        $tasks += ":${module}:assemble$Configuration"
        if ($Configuration -eq 'Release' -or $Verify) { $tasks += ":${module}:bundleRelease" }
    }
    Write-Host "[Pluxy] Build $Configuration ($Application), verification complete : $($Verify -or $Configuration -eq 'Release')."
    Push-Location (Join-Path $repository 'client')
    try { & ./gradlew.bat @tasks --console=plain --build-cache --parallel; if ($LASTEXITCODE -ne 0) { throw 'Gradle a echoue ; aucun nouvel APK copie dans dist.' } } finally { Pop-Location }
    $dist = Join-Path $repository 'dist'
    [IO.Directory]::CreateDirectory($dist) | Out-Null
    $revision = Invoke-AndroidCommand 'git.exe' @('-C', $repository, 'rev-parse', 'HEAD')
    $status = Invoke-AndroidCommand 'git.exe' @('-C', $repository, 'status', '--porcelain')
    foreach ($module in $modules) {
        $flavor = $Configuration.ToLowerInvariant()
        $source = Join-Path $repository "client/$module/build/outputs/apk/$flavor/$module-$flavor.apk"
        $info = Get-AndroidApkInfo $source $environment
        $expected = if ($module -eq 'app') { 'com.pluxy.tv' } else { 'com.pluxy.server' }
        if ($info.Package -ne $expected -or $info.VersionName -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,50}$') { throw 'Package ou version APK inattendus.' }
        $prefix = if ($module -eq 'app') { 'Pluxy' } else { 'Pluxy-Server-Android' }
        $applicationName = if ($module -eq 'app') { 'player' } else { 'server' }
        $suffix = if ($Configuration -eq 'Debug') { '-debug' } else { '' }
        $name = "$prefix-$($info.VersionName)$suffix.apk"
        $destination = Join-Path $dist $name
        $temporaryApk = "$destination.$([guid]::NewGuid().ToString('N')).tmp"
        Copy-Item -LiteralPath $source -Destination $temporaryApk
        if ((Get-FileHash -LiteralPath $temporaryApk).Hash.ToLowerInvariant() -ne $info.Sha256) { throw "Copie APK incomplete : $name" }
        if (Test-Path -LiteralPath $destination) { [IO.File]::Replace($temporaryApk, $destination, [NullString]::Value) } else { [IO.File]::Move($temporaryApk, $destination) }
        if ($Configuration -eq 'Release') { Copy-Item -LiteralPath (Join-Path $repository "client/$module/build/outputs/bundle/release/$module-release.aab") -Destination (Join-Path $dist "$prefix-$($info.VersionName).aab") -Force }
        $artifact = [ordered]@{ name = $name; package = $info.Package; version_name = $info.VersionName; version_code = $info.VersionCode; min_sdk = $info.MinSdk; sha256 = $info.Sha256; signing_cert_sha256 = @($info.Certificates) }
        $manifest = [ordered]@{ schema = 1; commit = $revision.Text.Trim(); dirty = -not [string]::IsNullOrWhiteSpace($status.Text); run_url = $null; channel = $flavor; created_at = [DateTime]::UtcNow.ToString('o'); verified = [bool]($Verify -or $Configuration -eq 'Release'); artifacts = @($artifact) }
        $manifestPath = Join-Path $dist "BUILD_INFO-$applicationName-$flavor.json"
        $temporary = "$manifestPath.$([guid]::NewGuid().ToString('N')).tmp"
        [IO.File]::WriteAllText($temporary, ($manifest | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
        if (Test-Path -LiteralPath $manifestPath) { [IO.File]::Replace($temporary, $manifestPath, [NullString]::Value) } else { [IO.File]::Move($temporary, $manifestPath) }
        Write-Host "[Pluxy] Pret : dist/$name (code $($info.VersionCode))."
    }
    Write-Host "[Pluxy] Termine en $([math]::Round($timer.Elapsed.TotalSeconds, 1)) s. Versions, certificats et SHA-256 dans dist/BUILD_INFO-*.json."
    if (-not $Verify -and $Configuration -eq 'Debug') { Write-Host '[Pluxy] Build rapide local. -Verify lance tests, lint et bundles. GitHub garde les controles obligatoires avant publication.' }
} catch { Write-Host "[ERREUR] $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
finally { foreach ($name in $localSigningVariables) { [Environment]::SetEnvironmentVariable($name, $null) } }
