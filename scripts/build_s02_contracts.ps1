$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
python (Join-Path $PSScriptRoot 'build_s02_contracts.py') --root $root
