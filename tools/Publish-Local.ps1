[CmdletBinding()]
param(
    [ValidateSet('Both', 'Android', 'Windows')][string]$Target = 'Both',
    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot
$python = Join-Path $repository 'server/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python)) {
    throw 'Preparez server/.venv avec requirements.txt et requirements-dev.txt avant de publier.'
}
$arguments = @((Join-Path $PSScriptRoot 'publish_local.py'), '--target', $Target.ToLowerInvariant())
if ($Publish) { $arguments += '--publish' }
& $python @arguments
if ($LASTEXITCODE -ne 0) { throw 'La preparation ou la publication locale a echoue. Voir le diagnostic ci-dessus.' }
