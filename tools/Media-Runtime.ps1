$ErrorActionPreference = 'Stop'

function Initialize-PCMediaRuntime {
    param([string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'Programs\Pluxy\FFmpeg'))
    $InstallDirectory = [IO.Path]::GetFullPath($InstallDirectory)
    $bin = Join-Path $InstallDirectory 'bin'
    $missing = @('ffmpeg', 'ffprobe') | Where-Object {
        -not (Get-Command $_ -CommandType Application -ErrorAction SilentlyContinue) -and
        -not (Test-Path -LiteralPath (Join-Path $bin ($_.ToString() + '.exe')) -PathType Leaf)
    }
    if ($missing) {
        Write-Host '[Pluxy] Installation de FFmpeg et ffprobe pour les miniatures et la lecture...'
        # Build Windows référencé par ffmpeg.org ; version et empreinte figées.
        $uri = 'https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip'
        $expected = '60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba'
        $parent = [IO.Path]::GetDirectoryName($InstallDirectory)
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
        $stage = Join-Path $parent ('ffmpeg-install-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $stage | Out-Null
        $archive = Join-Path $stage 'ffmpeg.zip'
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $uri -OutFile $archive -UseBasicParsing -TimeoutSec 300
            if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
                throw 'Le téléchargement de FFmpeg est incomplet ou ne correspond pas à la version attendue.'
            }
            Expand-Archive -LiteralPath $archive -DestinationPath $stage
            $build = Join-Path $stage 'ffmpeg-9.0.2-essentials_build'
            foreach ($name in @('ffmpeg', 'ffprobe')) {
                $executable = Join-Path $build ('bin\' + $name + '.exe')
                if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw 'Archive FFmpeg incomplète.' }
                & $executable -version *> $null
                if ($LASTEXITCODE -ne 0) { throw 'FFmpeg ne peut pas démarrer sur ce PC.' }
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
    if (Test-Path -LiteralPath $bin -PathType Container) { $env:PATH = $bin + [IO.Path]::PathSeparator + $env:PATH }
    return $bin
}
