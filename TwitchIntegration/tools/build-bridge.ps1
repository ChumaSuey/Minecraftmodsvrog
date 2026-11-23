param(
    [switch]$DebugBuild,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ExtraArgs
)

$ErrorActionPreference = 'Stop'

Push-Location (Join-Path $PSScriptRoot '..\bridge')
try {
    $cargoArgs = @('build')
    if (-not $DebugBuild) {
        $cargoArgs += '--release'
    }
    $cargoArgs += $ExtraArgs

    & cargo @cargoArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    Write-Host "Bridge built. Gradle's copyBridge task packages it into the mod jar."
} finally {
    Pop-Location
}
