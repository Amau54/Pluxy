param([switch]$StatusOnly)
$ErrorActionPreference = 'Stop'
$statusPath = Join-Path $env:LOCALAPPDATA 'Programs/Pluxy/Downloads/download-status.json'
if (Test-Path -LiteralPath $statusPath) {
    $status = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
    Write-Host $status.message
    $age = ([DateTime]::UtcNow - [DateTime]::Parse($status.updated_at).ToUniversalTime()).TotalSeconds
    if ($age -gt 30 -and -not $status.verified) { Write-Host 'Aucune actualisation récente. Relancez sans -StatusOnly pour reprendre la préparation.' }
} else { Write-Host 'Aucun téléchargement de composant en cours enregistré.' }
if (-not $StatusOnly) {
    # Start-PC owns the preparation mutex: a second invocation cannot modify its files.
    & (Join-Path $PSScriptRoot 'Start-PC.ps1') -PrepareOnly
}
