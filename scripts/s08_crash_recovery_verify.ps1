[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$source = Join-Path $PSScriptRoot "S08CrashRecoveryProbe.java"
$classes = Join-Path ([IO.Path]::GetTempPath()) "s08-crash-classes-$PID"
$table = "s08_crash_$PID"
$database = "s08_crash_db_$PID"
$saved = @{}
$names = @("S08_CRASH_ADMIN_URL", "S08_CRASH_DB_URL", "S08_CRASH_DB_USER", "S08_CRASH_DB_PASSWORD", "S08_CRASH_DB_NAME", "S08_CRASH_TABLE")

function Require-Environment([string]$name) {
    $value = [Environment]::GetEnvironmentVariable($name, "Process")
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Missing process variable: $name" }
    return $value
}

function Invoke-Probe([string]$mode) {
    & $script:java -cp ($script:connector + ";" + $classes) S08CrashRecoveryProbe $mode
    if ($LASTEXITCODE -ne 0) { throw "Probe failed: $mode, exit code $LASTEXITCODE" }
}

function Invoke-Admin([string]$mode) {
    & $script:java -cp ($script:connector + ";" + $classes) S08CrashRecoveryProbe $mode
    if ($LASTEXITCODE -ne 0) { throw "Admin probe failed: $mode, exit code $LASTEXITCODE" }
}

try {
    $javaHome = $env:JAVA_HOME
    if ([string]::IsNullOrWhiteSpace($javaHome)) { throw "JAVA_HOME is required" }
    $script:java = Join-Path $javaHome "bin\java.exe"
    $javac = Join-Path $javaHome "bin\javac.exe"
    if (-not (Test-Path $script:java) -or -not (Test-Path $javac)) { throw "Java tools are unavailable" }
    $connectorRoot = Join-Path $env:USERPROFILE ".m2\repository\com\mysql\mysql-connector-j"
    $connector = Get-ChildItem $connectorRoot -Filter "mysql-connector-j-*.jar" -Recurse | Where-Object { $_.Name -notmatch "-(sources|javadoc)\.jar$" } | Sort-Object FullName -Descending | Select-Object -First 1
    if ($null -eq $connector) { throw "MySQL connector was not found" }
    $script:connector = $connector.FullName
    foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }
    $env:S08_CRASH_TABLE = $table
    $env:S08_CRASH_DB_NAME = $database
    foreach ($name in @("S08_CRASH_ADMIN_URL", "S08_CRASH_DB_USER", "S08_CRASH_DB_PASSWORD")) { Require-Environment $name | Out-Null }
    $env:S08_CRASH_DB_URL = "jdbc:mysql://127.0.0.1:3306/$database"
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    & $javac -cp $script:connector -d $classes $source
    if ($LASTEXITCODE -ne 0) { throw "Probe compilation failed" }

    Invoke-Admin "create-database"
    Invoke-Probe "init"
    $crash = Start-Process -FilePath $script:java -ArgumentList @("-cp", ($script:connector + ";" + $classes), "S08CrashRecoveryProbe", "crash") -PassThru -Wait
    if ($crash.ExitCode -eq 0) { throw "Crash process exited cleanly" }
    $state = & $script:java -cp ($script:connector + ";" + $classes) S08CrashRecoveryProbe read
    if ($state -notmatch "S08CRASH\|100\|0") { throw "Uncommitted transaction was not rolled back: $state" }
    Invoke-Probe "commit"
    Invoke-Probe "retry"
    $committed = & $script:java -cp ($script:connector + ";" + $classes) S08CrashRecoveryProbe read
    if ($committed -notmatch "S08CRASH\|90\|0") { throw "Committed idempotent retry changed state: $committed" }
    Write-Output "Crash recovery probe passed"
}
catch {
    Write-Error $_
    exit 1
}
finally {
    try {
        if (Test-Path (Join-Path $classes "S08CrashRecoveryProbe.class")) {
            & $script:java -cp ($script:connector + ";" + $classes) S08CrashRecoveryProbe cleanup | Out-Null
        }
    }
    catch { }
    try {
        if (Test-Path (Join-Path $classes "S08CrashRecoveryProbe.class")) {
            & $script:java -cp ($script:connector + ";" + $classes) S08CrashRecoveryProbe drop-database | Out-Null
        }
    }
    catch { }
    try { if (Test-Path $classes) { Remove-Item -LiteralPath $classes -Recurse -Force } } catch { }
    foreach ($name in $names) {
        if ($null -eq $saved[$name]) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
        else { Set-Item "Env:$name" $saved[$name] }
    }
}
