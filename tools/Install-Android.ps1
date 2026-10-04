[CmdletBinding()]
param(
    [ValidateSet('Player', 'Server')][string]$Application = 'Player',
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Debug',
    [string]$Apk, [string]$Device, [string]$Address, [string]$PairAddress,
    [switch]$Launch, [switch]$NonInteractive
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Android-Deployment.ps1"
$repository = Split-Path $PSScriptRoot
$temporaryDirectory = $null
$timer = [Diagnostics.Stopwatch]::StartNew()
try {
    $environment = Find-AndroidEnvironment $repository
    $expected = if ($Application -eq 'Player') { 'com.pluxy.tv' } else { 'com.pluxy.server' }
    if (-not $Apk) {
        $manifestPath = Join-Path $repository "dist/BUILD_INFO-$($Application.ToLowerInvariant())-$($Configuration.ToLowerInvariant()).json"
        if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'Aucun build local identifie. Lancez Build-APK.bat ou indiquez -Apk.' }
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        $entry = @($manifest.artifacts | Where-Object { $_.package -eq $expected })
        if ($manifest.schema -ne 1 -or $entry.Count -ne 1 -or [IO.Path]::GetFileName($entry[0].name) -ne $entry[0].name) { throw 'Manifest APK incoherent ; reconstruisez ou indiquez -Apk.' }
        $Apk = Join-Path $repository "dist/$($entry[0].name)"
        if ((Get-FileHash -LiteralPath $Apk).Hash.ToLowerInvariant() -ne $entry[0].sha256) { throw 'Le fichier APK differe du build identifie ; reconstruction necessaire.' }
    }
    $candidate = Get-AndroidApkInfo $Apk $environment
    if ($candidate.Package -ne $expected) { throw "Mauvaise application : APK $($candidate.Package), attendu $expected." }
    Write-Host "[Pluxy] $($candidate.Package) $($candidate.VersionName), code $($candidate.VersionCode)."
    foreach ($endpoint in @($Address, $PairAddress)) {
        if (-not $endpoint) { continue }
        if ($endpoint -notmatch '^(?:[a-zA-Z0-9][a-zA-Z0-9.-]*|\[[a-fA-F0-9:]+\]):([0-9]{1,5})$' -or [int]$Matches[1] -lt 1 -or [int]$Matches[1] -gt 65535) { throw 'Adresse attendue : IP:port. Utilisez le port du debogage sans fil (5555 sur les anciennes TV).' }
    }
    if ($PairAddress) {
        if ($NonInteractive) { throw 'Appairage interactif requis : retirez -NonInteractive.' }
        Write-Host '[Pluxy] Saisissez le code affiche par Android pour associer cet ordinateur.'
        & $environment.Adb pair $PairAddress
        if ($LASTEXITCODE -ne 0) { throw 'Appairage ADB refuse. Verifiez le code et le port.' }
    }
    if ($Address) {
        $connected = Invoke-AndroidCommand $environment.Adb @('connect', $Address)
        if ($connected.Code -ne 0 -or $connected.Text -notmatch '(already )?connected to') { throw "Connexion ADB echouee : $($connected.Text)" }
        $Device = $Address
    }
    $devicesResult = Invoke-AndroidCommand $environment.Adb @('devices')
    if ($devicesResult.Code -ne 0) { throw $devicesResult.Text }
    $devices = @([regex]::Matches($devicesResult.Text, '(?m)^([^\s]+)\s+device\s*$') | ForEach-Object { $_.Groups[1].Value })
    if (-not $Device) {
        if ($devices.Count -eq 1) { $Device = $devices[0] }
        elseif ($NonInteractive) { throw 'Choisissez un appareil connecte avec -Device ou -Address IP:port.' }
        else {
            Write-Host "Appareils disponibles : $($devices -join ', ')."
            $answer = Read-Host 'Numero de serie USB/emulateur, ou adresse Wi-Fi IP:port'
            if ($answer -match ':\d+$') {
                if ($answer -notmatch '^[a-zA-Z0-9][a-zA-Z0-9.-]*:([0-9]{1,5})$' -or [int]$Matches[1] -lt 1 -or [int]$Matches[1] -gt 65535) { throw 'Adresse ou port ADB invalide.' }
                $connected = Invoke-AndroidCommand $environment.Adb @('connect', $answer)
                if ($connected.Code -ne 0 -or $connected.Text -notmatch '(already )?connected to') { throw "Connexion ADB echouee : $($connected.Text)" }
            }
            $Device = $answer
        }
    }
    if (-not $Device -or $Device -match '\s' -or $Device.StartsWith('-')) { throw 'Numero de serie ADB invalide.' }
    $state = Invoke-AndroidCommand $environment.Adb @('-s', $Device, 'get-state')
    if ($state.Code -ne 0 -or $state.Text.Trim() -ne 'device') { throw (Get-AndroidInstallFailure $state.Text) }
    $sdk = Invoke-AndroidCommand $environment.Adb @('-s', $Device, 'shell', 'getprop', 'ro.build.version.sdk')
    if ($sdk.Code -ne 0 -or $sdk.Text.Trim() -notmatch '^\d+$') { throw 'Impossible de lire la version Android.' }
    $installed = $null
    $paths = Invoke-AndroidCommand $environment.Adb @('-s', $Device, 'shell', 'pm', 'path', $expected)
    # Android's pm returns exit 1 with no output when the app is not installed.
    if ($paths.Code -ne 0 -and ($paths.Code -ne 1 -or -not [string]::IsNullOrWhiteSpace($paths.Text))) { throw "Impossible de verifier le package : $($paths.Text)" }
    $base = [regex]::Match($paths.Text, '(?m)^package:(.+/base\.apk)\s*$')
    if (-not $base.Success) { $base = [regex]::Match($paths.Text, '(?m)^package:(.+\.apk)\s*$') }
    if ($base.Success) {
        $temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) "pluxy-apk-$([guid]::NewGuid().ToString('N'))"
        [IO.Directory]::CreateDirectory($temporaryDirectory) | Out-Null
        $installedApk = Join-Path $temporaryDirectory 'installed.apk'
        $pull = Invoke-AndroidCommand $environment.Adb @('-s', $Device, 'pull', $base.Groups[1].Value.Trim(), $installedApk)
        if ($pull.Code -ne 0) { throw "Certificat installe impossible a verifier : $($pull.Text). Aucune modification effectuee." }
        $installed = Get-AndroidApkInfo $installedApk $environment
    }
    Assert-AndroidUpdate $candidate $installed ([int]$sdk.Text.Trim())
    if ($installed -and $installed.Sha256 -eq $candidate.Sha256) { Write-Host '[Pluxy] Cette APK est deja installee ; installation ignoree.' }
    else {
        Write-Host '[Pluxy] Installation en conservant les donnees...'
        $installation = Invoke-AndroidCommand $environment.Adb @('-s', $Device, 'install', '-r', '--streaming', $candidate.Path)
        if ($installation.Code -ne 0 -or $installation.Text -notmatch '(?m)^Success\s*$') { throw (Get-AndroidInstallFailure $installation.Text) }
        $confirmation = Invoke-AndroidCommand $environment.Adb @('-s', $Device, 'shell', 'dumpsys', 'package', $expected)
        if ($confirmation.Code -ne 0 -or $confirmation.Text -notmatch "versionCode=$($candidate.VersionCode)(?:\s|$)") { throw 'Installation annoncee reussie mais version attendue non confirmee.' }
        Write-Host "[Pluxy] Version $($candidate.VersionName) installee et confirmee."
    }
    if ($Launch) {
        $component = if ($Application -eq 'Player') { 'com.pluxy.tv/.ui.MainActivity' } else { 'com.pluxy.server/.MainActivity' }
        $opened = Invoke-AndroidCommand $environment.Adb @('-s', $Device, 'shell', 'am', 'start', '-n', $component)
        if ($opened.Code -ne 0 -or $opened.Text -match 'Error:|Exception') { throw "APK installee, ouverture impossible : $($opened.Text)" }
    }
    Write-Host "[Pluxy] Termine en $([math]::Round($timer.Elapsed.TotalSeconds, 1)) s."
} catch { Write-Host "[ERREUR] $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
finally {
    if ($temporaryDirectory -and (Test-Path -LiteralPath $temporaryDirectory)) {
        $resolved = (Resolve-Path -LiteralPath $temporaryDirectory).Path
        $root = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        if ($resolved.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($resolved) -like 'pluxy-apk-*') { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
}
