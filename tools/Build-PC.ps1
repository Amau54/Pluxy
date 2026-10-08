param([switch]$AppOnly, [string]$OutputDirectory, [string]$DependencyDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo 'dist-pc' }
if (-not $DependencyDirectory) { $DependencyDirectory = Join-Path $repo 'artifacts\desktop-deps' }
$output = [IO.Path]::GetFullPath($OutputDirectory)
$deps = [IO.Path]::GetFullPath($DependencyDirectory)
$payload = Join-Path $output 'app'
. (Join-Path $PSScriptRoot 'PC-Launcher.ps1')
$locked = Get-Content -LiteralPath (Join-Path $repo 'desktop\dependencies.json') -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force -Path $output,$deps,$payload | Out-Null

function Get-BuildDependency {
    param([string]$Url, [string]$Target, [string]$Sha256)
    if (-not (Test-Path -LiteralPath $Target)) {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $Url -OutFile ($Target + '.tmp') -UseBasicParsing
        Move-Item -LiteralPath ($Target + '.tmp') -Destination $Target -Force
    }
    if ((Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256) { throw "Empreinte incorrecte : $Target" }
}

$webview = Join-Path $deps 'webview2.zip'
Get-BuildDependency -Url "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$($locked.webview2.version)/microsoft.web.webview2.$($locked.webview2.version).nupkg" -Target $webview -Sha256 $locked.webview2.sha256
$sdk = Join-Path $deps 'webview2'
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'lib\net462\Microsoft.Web.WebView2.Core.dll'))) { Expand-Archive -LiteralPath $webview -DestinationPath $sdk -Force }

$mpv = Join-Path $deps 'mpv.7z'
Get-BuildDependency -Url $locked.mpv.url -Target $mpv -Sha256 $locked.mpv.sha256
$engine = Join-Path $deps 'mpv'
if (-not (Test-Path -LiteralPath (Join-Path $engine 'mpv.exe'))) {
    New-Item -ItemType Directory -Force -Path $engine | Out-Null
    & tar.exe -xf $mpv -C $engine
    if ($LASTEXITCODE -ne 0) { throw 'Extraction du moteur vidéo impossible.' }
}

# La sortie de compilation ne contient jamais les données d'une installation.
if (Test-Path -LiteralPath $payload) {
    $resolved = (Resolve-Path -LiteralPath $payload).Path
    if ($resolved -ne (Join-Path $output 'app') -or $resolved -eq $repo) { throw 'Dossier de sortie inattendu.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $payload,(Join-Path $payload 'ui'),(Join-Path $payload 'media'),(Join-Path $payload 'tools'),(Join-Path $payload 'server') | Out-Null
Copy-Item -LiteralPath (Join-Path $sdk 'lib\net462\Microsoft.Web.WebView2.Core.dll'),(Join-Path $sdk 'lib\net462\Microsoft.Web.WebView2.WinForms.dll'),(Join-Path $sdk 'runtimes\win-x64\native\WebView2Loader.dll') -Destination $payload
Copy-Item -LiteralPath (Join-Path $repo 'assets\pluxy.ico') -Destination $payload
Copy-Item -LiteralPath (Join-Path $repo 'assets\pluxy-logo.png') -Destination (Join-Path $payload 'ui\logo.png')
Copy-Item -Path (Join-Path $repo 'desktop\ui\*') -Destination (Join-Path $payload 'ui')
Copy-Item -LiteralPath (Join-Path $engine 'mpv.exe'),(Join-Path $engine 'd3dcompiler_43.dll') -Destination (Join-Path $payload 'media')
foreach ($name in @('Start-PC.ps1','PC-Launcher.ps1','Media-Runtime.ps1')) { Copy-Item -LiteralPath (Join-Path $repo "tools\$name") -Destination (Join-Path $payload 'tools') }
foreach ($name in @('run.py','requirements.txt','config.default.json')) { Copy-Item -LiteralPath (Join-Path $repo "server\$name") -Destination (Join-Path $payload 'server') }
$defaults = Get-Content -LiteralPath (Join-Path $payload 'server\config.default.json') -Raw | ConvertFrom-Json
$defaults.server.media_dirs = @()
[IO.File]::WriteAllText((Join-Path $payload 'server\config.default.json'), ($defaults | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding($false)))
$source = Join-Path $repo 'server\pluxy'
Get-ChildItem -LiteralPath $source -File -Recurse | Where-Object { $_.Extension -in @('.py','.html','.js','.css','.png','.ico','.svg') -and $_.FullName -notmatch '[\\/]__pycache__[\\/]' } | ForEach-Object {
    $relative = $_.FullName.Substring($source.Length + 1)
    $target = Join-Path $payload "server\pluxy\$relative"
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $target
}

$licenseDir = Join-Path $payload 'licenses'
New-Item -ItemType Directory -Force -Path $licenseDir | Out-Null
foreach ($file in @('Copyright','LICENSE.GPL','LICENSE.LGPL')) {
    $cached = Join-Path $deps "mpv-$file.txt"
    if (-not (Test-Path -LiteralPath $cached)) { Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/mpv-player/mpv/36bf3d5290/$file" -OutFile $cached }
    Copy-Item -LiteralPath $cached -Destination (Join-Path $licenseDir "mpv-$file.txt")
}
Copy-Item -LiteralPath (Join-Path $sdk 'LICENSE.txt') -Destination (Join-Path $licenseDir 'WebView2.txt')
Copy-Item -LiteralPath (Join-Path $sdk 'NOTICE.txt') -Destination (Join-Path $licenseDir 'WebView2-NOTICE.txt')
Copy-Item -LiteralPath (Join-Path $repo 'desktop\THIRD_PARTY.txt') -Destination $licenseDir

$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Le compilateur .NET Framework 4.8 de Windows est requis.' }
$arguments = @('/nologo','/target:winexe','/platform:x64','/optimize+','/utf8output',"/out:$(Join-Path $payload 'Pluxy.exe')","/win32icon:$(Join-Path $payload 'pluxy.ico')","/win32manifest:$(Join-Path $repo 'desktop\app.manifest')")
foreach ($reference in @('System.dll','System.Core.dll','System.Drawing.dll','System.Windows.Forms.dll','System.Net.Http.dll','System.Web.dll','System.Web.Extensions.dll',(Join-Path $payload 'Microsoft.Web.WebView2.Core.dll'),(Join-Path $payload 'Microsoft.Web.WebView2.WinForms.dll'))) { $arguments += "/reference:$reference" }
$arguments += @(Get-ChildItem -LiteralPath (Join-Path $repo 'desktop') -Filter '*.cs' -File | ForEach-Object { $_.FullName })
$result = Invoke-PCNative -FilePath $compiler -Arguments $arguments -TimeoutSeconds 120
if ($result.Code -ne 0) { throw "Compilation PC impossible : $($result.Output) $($result.Error)" }
$result.Output.Trim() | Write-Output
if ($AppOnly) { Write-Output "Application : $payload"; return }

$innoSetup = Join-Path $deps 'inno-setup.exe'
Get-BuildDependency -Url $locked.inno.url -Target $innoSetup -Sha256 $locked.inno.sha256
$inno = Join-Path $deps 'inno'
if (-not (Test-Path -LiteralPath (Join-Path $inno 'ISCC.exe'))) {
    $result = Invoke-PCNative -FilePath $innoSetup -Arguments @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',"/DIR=$inno") -TimeoutSeconds 120
    if ($result.Code -ne 0) { throw 'Installation du compilateur Inno Setup impossible.' }
}
$bootstrap = Join-Path $deps 'MicrosoftEdgeWebview2Setup.exe'
if (-not (Test-Path -LiteralPath $bootstrap)) { Invoke-WebRequest -UseBasicParsing -Uri 'https://go.microsoft.com/fwlink/p/?LinkId=2124703' -OutFile $bootstrap }
$signature = Get-AuthenticodeSignature -LiteralPath $bootstrap
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') { throw 'Signature Microsoft du composant WebView2 invalide.' }
$result = Invoke-PCNative -FilePath (Join-Path $inno 'ISCC.exe') -Arguments @("/DPayload=$payload","/DBuildOutput=$output","/DBootstrapper=$bootstrap",(Join-Path $repo 'desktop\installer.iss')) -TimeoutSeconds 300
if ($result.Code -ne 0) { throw "Installateur PC impossible : $($result.Output) $($result.Error)" }
$installer = Join-Path $output 'Pluxy-Setup.exe'
$commit = (& git -C $repo rev-parse HEAD).Trim()
$digest = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = [ordered]@{ version='1.8.1'; commit=$commit; sha256=$digest; size=(Get-Item -LiteralPath $installer).Length; webview2=$locked.webview2.version; mpv=$locked.mpv.url; bootstrapper_sha256=(Get-FileHash -LiteralPath $bootstrap -Algorithm SHA256).Hash.ToLowerInvariant() }
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'PC_BUILD_INFO.json') -Encoding UTF8
"$digest  Pluxy-Setup.exe" | Set-Content -LiteralPath (Join-Path $output 'PC_SHA256SUMS.txt') -Encoding ASCII
Write-Output "Installateur : $installer ($digest)"
