param(
    [string]$Python = "python"
)

$ErrorActionPreference = "Stop"
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$dependencyDir = Join-Path $tempRoot ("agenttravel-s11-contract-deps-" + [guid]::NewGuid().ToString("N"))
$exitCode = 1

New-Item -ItemType Directory -Path $dependencyDir | Out-Null
try {
    & $Python -m pip install --disable-pip-version-check --no-input --target $dependencyDir `
        -r scripts/requirements-s11-contracts.lock.txt
    if ($LASTEXITCODE -ne 0) {
        throw "契约校验依赖安装失败"
    }
    $env:PYTHONPATH = $dependencyDir
    & $Python scripts/validate_s11_contracts.py
    $exitCode = $LASTEXITCODE
}
finally {
    $resolved = [IO.Path]::GetFullPath($dependencyDir)
    $insideTemp = $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)
    $expectedName = [IO.Path]::GetFileName($resolved).StartsWith("agenttravel-s11-contract-deps-")
    if ($insideTemp -and $expectedName -and (Test-Path -LiteralPath $resolved)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

exit $exitCode
