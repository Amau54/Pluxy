$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 6) {
    Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Force
    Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -Force
}

function Write-PCPreparationStatus {
    param([string]$Message)
    Write-Host ('[Pluxy] ' + $Message)
}

function Save-PCComponent {
    param([string]$Uri, [string]$Destination, [string]$Sha256, [string]$Label,
          [int]$StallSeconds = 25, [int]$Attempts = 4)
    # A stable destination preserves completed bytes across application restarts.
    $directory = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $curl = Join-Path $env:SystemRoot 'System32/curl.exe'
    if (-not (Test-Path -LiteralPath $curl)) { throw 'curl.exe est requis pour télécharger les composants Windows.' }
    $statusPath = Join-Path $directory 'download-status.json'
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        if ((Test-Path -LiteralPath $Destination) -and
            (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $Sha256) {
            Write-PCPreparationStatus "$Label · téléchargement vérifié"
            return
        }
        $initial = if (Test-Path -LiteralPath $Destination) { (Get-Item -LiteralPath $Destination).Length } else { 0 }
        $errorFile = Join-Path $directory 'download-error.txt'
        $headers = Join-Path $directory 'download-headers.txt'
        $arguments = @('--location','--fail','--silent','--show-error','--connect-timeout','15',
            '--speed-limit','1024','--speed-time',"$StallSeconds",'--max-time','600',
            '--continue-at','-','--dump-header',$headers,'--output',$Destination,$Uri)
        $quoted = $arguments | ForEach-Object { '"' + $_.Replace('"','\"') + '"' }
        Write-PCPreparationStatus "$Label · connexion · tentative $attempt/$Attempts"
        $process = Start-Process -FilePath $curl -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardError $errorFile
        # Windows PowerShell can lose ExitCode after a fast child exits unless its handle is retained.
        $processHandle = $process.Handle
        $lastBytes = $initial; $lastChange = [DateTime]::UtcNow; $lastTick = $lastChange
        try {
            do {
                $now = [DateTime]::UtcNow
                $bytes = if (Test-Path -LiteralPath $Destination) { (Get-Item -LiteralPath $Destination).Length } else { 0 }
                if ($bytes -gt $lastBytes) { $lastChange = $now }
                $total = 0L
                if (Test-Path -LiteralPath $headers) {
                    $text = [string](Get-Content -LiteralPath $headers -Raw -ErrorAction SilentlyContinue)
                    $ranges = [regex]::Matches($text, '(?im)^content-range:\s*bytes\s+\d+-\d+/(\d+)')
                    $lengths = [regex]::Matches($text, '(?im)^content-length:\s*(\d+)')
                    if ($ranges.Count) { $total = [long]$ranges[$ranges.Count-1].Groups[1].Value }
                    elseif ($lengths.Count) { $total = [long]$lengths[$lengths.Count-1].Groups[1].Value }
                }
                $speed = [Math]::Max(0, ($bytes-$lastBytes)/[Math]::Max(0.1,($now-$lastTick).TotalSeconds))
                $percent = if ($total -gt 0) { [Math]::Min(100,[int]($bytes*100.0/$total)) } else { -1 }
                $size = '{0:N1} Mo' -f ($bytes/1MB)
                $detail = if ($percent -ge 0) { "$percent % · $size / $('{0:N1}' -f ($total/1MB)) Mo" } else { $size }
                $message = "$Label · $detail · $('{0:N1}' -f ($speed/1MB)) Mo/s"
                if (($now-$lastChange).TotalSeconds -ge 8) { $message += ' · attente du réseau…' }
                Write-PCPreparationStatus $message
                $state = @{ label=$Label; bytes=$bytes; total=$total; percent=$percent; bytes_per_second=$speed;
                    attempt=$attempt; updated_at=$now.ToString('o'); message=$message }
                [IO.File]::WriteAllText($statusPath, ($state | ConvertTo-Json -Compress))
                $lastBytes=$bytes; $lastTick=$now
                if (($now-$lastChange).TotalSeconds -ge $StallSeconds) {
                    if (-not $process.HasExited) { $process.Kill() }
                    break
                }
                if (-not $process.HasExited) { Start-Sleep -Milliseconds 1000 }
            } while (-not $process.HasExited)
            $process.WaitForExit()
            $code=$process.ExitCode
        } finally {
            if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
            $process.Dispose()
        }
        if ($code -eq 0 -and (Test-Path -LiteralPath $Destination)) {
            Write-PCPreparationStatus "$Label · vérification du téléchargement…"
            if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $Sha256) {
                Write-PCPreparationStatus "$Label · 100 % · téléchargement vérifié"
                [IO.File]::WriteAllText($statusPath, (@{ label=$Label; verified=$true; message="$Label · téléchargement vérifié"; updated_at=[DateTime]::UtcNow.ToString('o') } | ConvertTo-Json -Compress))
                return
            }
            # The complete response is corrupt: a byte resume cannot repair it.
            [IO.File]::WriteAllBytes($Destination, [byte[]]@())
        } elseif ($code -in @(33,36) -or ($code -eq 22 -and $initial -gt 0)) {
            # Server does not support resume, or a partial file exceeds its content.
            [IO.File]::WriteAllBytes($Destination, [byte[]]@())
        }
        if ($attempt -lt $Attempts) {
            Write-PCPreparationStatus "$Label · connexion interrompue, reprise automatique ($($attempt+1)/$Attempts)…"
            Start-Sleep -Seconds 1
        }
    }
    throw "$Label : téléchargement interrompu après $Attempts tentatives. Les données reçues sont conservées ; relancez la préparation pour reprendre."
}
