$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PC-Download.ps1')

function Initialize-PCMediaRuntime {
    param([string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'Programs\Pluxy\FFmpeg'))
    $InstallDirectory = [IO.Path]::GetFullPath($InstallDirectory)
    $bin = Join-Path $InstallDirectory 'bin'
    $missing = @('ffmpeg', 'ffprobe') | Where-Object {
        -not (Get-Command $_ -CommandType Application -ErrorAction SilentlyContinue) -and
        -not (Test-Path -LiteralPath (Join-Path $bin ($_.ToString() + '.exe')) -PathType Leaf)
    }
    $discSupport = $false
    $probe = Join-Path $bin 'ffprobe.exe'
    if (Test-Path -LiteralPath $probe -PathType Leaf) {
        $protocols = & $probe -hide_banner -protocols 2>$null | Out-String
        $formats = & $probe -hide_banner -demuxers 2>$null | Out-String
        $discSupport = $protocols -match '(?m)^\s*bluray\s*$' -and $formats -match '\bdvdvideo\b'
    }
    if ($missing -or -not $discSupport) {
        Write-Host '[Pluxy] Installation de FFmpeg et ffprobe pour les miniatures et la lecture...'
        # Build Windows référencé par ffmpeg.org ; version et empreinte figées.
        $uri = 'https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-full_build.7z'
        $expected = 'f0e46253c70dfe902bac915dfb4224f0cf1b7c6eeab9da2ccc9a5581f9a71b13'
        $parent = [IO.Path]::GetDirectoryName($InstallDirectory)
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
        $stage = Join-Path $parent ('ffmpeg-install-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $stage | Out-Null
        $archive = Join-Path $parent 'Downloads/ffmpeg-9.0.2-full.7z'
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Save-PCComponent -Uri $uri -Destination $archive -Sha256 $expected -Label 'Moteur vidéo'
            if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
                throw 'Le téléchargement de FFmpeg est incomplet ou ne correspond pas à la version attendue.'
            }
            Write-PCPreparationStatus 'Installation du moteur vidéo…'
            & "$env:SystemRoot\System32\tar.exe" -xf $archive -C $stage
            if ($LASTEXITCODE -ne 0) { throw 'Extraction du moteur de lecture impossible.' }
            $build = Join-Path $stage 'ffmpeg-9.0.2-full_build'
            foreach ($name in @('ffmpeg', 'ffprobe')) {
                $executable = Join-Path $build ('bin\' + $name + '.exe')
                if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw 'Archive FFmpeg incomplète.' }
                & $executable -version *> $null
                if ($LASTEXITCODE -ne 0) { throw 'FFmpeg ne peut pas démarrer sur ce PC.' }
                $protocols = & $executable -hide_banner -protocols 2>$null | Out-String
                $formats = & $executable -hide_banner -demuxers 2>$null | Out-String
                if ($protocols -notmatch '(?m)^\s*bluray\s*$' -or $formats -notmatch '\bdvdvideo\b') {
                    throw 'Les modules Blu-ray et DVD sont absents du moteur téléchargé.'
                }
            }
            if (Test-Path -LiteralPath $InstallDirectory) {
                $resolved = (Resolve-Path -LiteralPath $InstallDirectory).ProviderPath
                if ([IO.Path]::GetDirectoryName($resolved) -ne $parent) { throw 'Emplacement FFmpeg inattendu.' }
                Move-Item -LiteralPath $InstallDirectory -Destination ($InstallDirectory + '-backup-' + [Guid]::NewGuid().ToString('N'))
            }
            Move-Item -LiteralPath $build -Destination $InstallDirectory
        } finally {
            $resolvedStage = (Resolve-Path -LiteralPath $stage).ProviderPath
            if ([IO.Path]::GetDirectoryName($resolvedStage) -eq $parent -and [IO.Path]::GetFileName($resolvedStage).StartsWith('ffmpeg-install-')) {
                Remove-Item -LiteralPath $resolvedStage -Recurse -Force
            }
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $bin '7z.exe')) -or
        -not (Test-Path -LiteralPath (Join-Path $bin '7z.dll'))) {
        $archive = Join-Path ([IO.Path]::GetDirectoryName($InstallDirectory)) 'Downloads/7z2606-x64.exe'
        Save-PCComponent -Uri 'https://github.com/ip7z/7zip/releases/download/26.06/7z2606-x64.exe' `
            -Destination $archive -Sha256 '9b697f65ecc2d9b0152f5ee96d72dde522a7827690349d9307935dacf40f42ec' -Label 'Navigation Blu-ray'
        New-Item -ItemType Directory -Path $bin -Force | Out-Null
        & "$env:SystemRoot\System32\tar.exe" -xf $archive -C $bin 7z.exe 7z.dll License.txt
        if ($LASTEXITCODE -ne 0) { throw 'Installation de la navigation Blu-ray impossible.' }
        Move-Item -LiteralPath (Join-Path $bin 'License.txt') -Destination (Join-Path $bin '7zip-License.txt') -Force
    }
    if (Test-Path -LiteralPath $bin -PathType Container) { $env:PATH = $bin + [IO.Path]::PathSeparator + $env:PATH }
    return $bin
}
