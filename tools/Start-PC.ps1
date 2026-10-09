param([switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
$mutex = $null
$ownsMutex = $false
try {
    . (Join-Path $PSScriptRoot 'PC-Launcher.ps1')
    if (-not $env:LOCALAPPDATA) { throw 'Le dossier du compte Windows est introuvable.' }
    $server = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\server'))
    $runtime = Join-Path $env:LOCALAPPDATA 'Programs\Pluxy\Python314'
    $dataBase = if ($env:PLUXY_DATA_DIR) { $env:PLUXY_DATA_DIR } else { $env:LOCALAPPDATA }
    $environment = Join-Path $dataBase 'Pluxy\PythonEnv'
    # Copies in different folders share one Python runtime; lock that too.
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $id = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($runtime.ToLowerInvariant()))).Replace('-', '') }
    finally { $hash.Dispose() }
    $mutex = New-Object Threading.Mutex($false, ('Local\Pluxy-PC-' + $id))
    try { $ownsMutex = $mutex.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) {
        if ($PrepareOnly) {
            try { $ownsMutex = $mutex.WaitOne(1200000) }
            catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
            if (-not $ownsMutex) { throw 'La preparation precedente ne repond plus. Relancez Pluxy.' }
        } else {
            Write-Host '[Pluxy] La preparation est deja en cours dans une autre fenetre.'
            exit 0
        }
    }
    Write-Host '[Pluxy] Verification du serveur PC...'
    $python = Initialize-PCEnvironment -ServerDirectory $server -RuntimeDirectory $runtime -EnvironmentDirectory $environment
    . (Join-Path $PSScriptRoot 'Media-Runtime.ps1')
    Initialize-PCMediaRuntime | Out-Null
    if ($PrepareOnly) { [pscustomobject]@{ Ready = $true; Python = $python } | ConvertTo-Json -Compress }
    else {
        Write-Host '[Pluxy] Ouverture de Pluxy. Son icone apparait dans la zone de notification.'
        Start-PCApplication -Python $python -ServerDirectory $server
    }
    exit 0
} catch {
    Write-Host "[ERREUR] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    if ($null -ne $mutex) { $mutex.Dispose() }
}
