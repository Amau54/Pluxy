param([string]$AppDirectory, [string]$OutputDirectory, [switch]$InstallFromSetup)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $AppDirectory) { $AppDirectory = Join-Path $repo 'dist-pc\app' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo 'artifacts\desktop-tests' }
$AppDirectory = [IO.Path]::GetFullPath($AppDirectory)
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
. (Join-Path $PSScriptRoot 'PC-Launcher.ps1')
. (Join-Path $PSScriptRoot 'Media-Runtime.ps1')
if ($InstallFromSetup) {
    $installer = Join-Path (Split-Path $AppDirectory -Parent) 'Pluxy-Setup.exe'
    $installed = Join-Path $env:ProgramW6432 'Pluxy'
    if (Test-Path -LiteralPath $installed) { throw 'Le test doit utiliser un PC sans installation Pluxy existante.' }
    $result = Invoke-PCNative -FilePath $installer -Arguments @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',"/LOG=$(Join-Path $OutputDirectory 'install.log')") -TimeoutSeconds 180
    if ($result.Code -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $installed 'Pluxy.exe'))) { throw 'Installation dans Program Files impossible.' }
    try {
        & $PSCommandPath -AppDirectory $installed -OutputDirectory $OutputDirectory
        if (Test-Path -LiteralPath (Join-Path $installed 'server\.venv')) { throw 'Python ne doit pas être créé dans Program Files.' }
        if (-not (Test-Path -LiteralPath (Join-Path $OutputDirectory 'server-data\Pluxy\PythonEnv\Scripts\python.exe'))) { throw 'Environnement Python absent des données utilisateur.' }
        Write-Output 'Installation dans Program Files et environnement Python séparé vérifiés'
    } finally {
        $uninstalled = Invoke-PCNative -FilePath (Join-Path $installed 'unins000.exe') -Arguments @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART') -TimeoutSeconds 120
        if ($uninstalled.Code -ne 0 -or (Test-Path -LiteralPath (Join-Path $installed 'Pluxy.exe'))) { throw 'Désinstallation du paquet de test impossible.' }
    }
    return
}
$exe = Join-Path $AppDirectory 'Pluxy.exe'
$binary = [IO.File]::ReadAllBytes($exe)
$pe = [BitConverter]::ToInt32($binary,60)
if ([BitConverter]::ToUInt16($binary,$pe+4) -ne 0x8664 -or [BitConverter]::ToUInt16($binary,$pe+24+68) -ne 2) { throw 'Pluxy doit être une application graphique Windows x64, sans console.' }
$forbidden = @(Get-ChildItem -LiteralPath $AppDirectory -File -Recurse | Where-Object { $_.FullName -match '[\\/](client|tests|\.git|__pycache__|\.venv)[\\/]' -or $_.Name -match '^(config\.json|.*\.keystore|.*\.jks|.*\.bat|credentials.*)$' })
if ($forbidden.Count) { throw 'Le paquet contient des fichiers de développement ou des données locales.' }
Initialize-PCMediaRuntime | Out-Null
$video = Join-Path $OutputDirectory 'seek-test.mkv'
$generated = Invoke-PCNative -FilePath (Get-Command ffmpeg.exe -CommandType Application).Source -Arguments @('-hide_banner','-loglevel','error','-y','-f','lavfi','-i','testsrc2=size=320x180:rate=2','-f','lavfi','-i','anullsrc=sample_rate=48000:channel_layout=stereo','-t','720','-c:v','libx264','-preset','ultrafast','-g','4','-c:a','aac',$video) -TimeoutSeconds 120
if ($generated.Code -ne 0) { throw "Création du média de test impossible : $($generated.Error)" }
$report = Join-Path $OutputDirectory 'native.json'
$result = Invoke-PCNative -FilePath $exe -Arguments @('--self-test','--video',$video,'--seek','600','--height','180','--report',$report) -TimeoutSeconds 90
$verified = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
if ($result.Code -ne 0 -or -not $verified.success) { throw "Test du lecteur PC en échec après $($verified.checks -join ', ') : $($verified.error)" }
$verified.checks | Write-Output

# Un port et des données séparés évitent de toucher à la bibliothèque du PC de test.
$listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$testBase = Join-Path $OutputDirectory 'server-data'
$testData = Join-Path $testBase 'Pluxy'
New-Item -ItemType Directory -Force -Path $testData | Out-Null
$config = Get-Content -LiteralPath (Join-Path $AppDirectory 'server\config.default.json') -Raw | ConvertFrom-Json
$config.server.port = $port
$config.server.media_dirs = @()
$config.server.autostart = $false
$config.discovery.enabled = $false
$config.metadata.enabled = $false
[IO.File]::WriteAllText((Join-Path $testData 'config.json'), ($config | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding($false)))
$previousData = $env:PLUXY_DATA_DIR
$startupKey = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Software\Microsoft\Windows\CurrentVersion\Run')
$previousStartup = $startupKey.GetValue('Pluxy')
$previousKind = if ($null -ne $previousStartup) { $startupKey.GetValueKind('Pluxy') } else { $null }
try {
    $env:PLUXY_DATA_DIR = $testBase
    $bootstrapReport = Join-Path $OutputDirectory 'bootstrap.json'
    $result = Invoke-PCNative -FilePath $exe -Arguments @('--self-test','--test-bootstrap','--report',$bootstrapReport) -TimeoutSeconds 1200
    $verified = Get-Content -LiteralPath $bootstrapReport -Raw | ConvertFrom-Json
    if ($result.Code -ne 0 -or -not $verified.success) { throw "Test du serveur PC en échec : $($verified.error)" }
    $verified.checks | Select-Object -Last 2 | Write-Output
} finally {
    $env:PLUXY_DATA_DIR = $previousData
    if ($null -ne $previousStartup) { $startupKey.SetValue('Pluxy', $previousStartup, $previousKind) }
    else { $startupKey.DeleteValue('Pluxy', $false) }
    $startupKey.Dispose()
}
