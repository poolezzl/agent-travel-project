[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$pathValue = $env:Path
Remove-Item Env:PATH -ErrorAction SilentlyContinue
$env:Path = $pathValue
$repoRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path ([IO.Path]::GetTempPath()) "s08-app-crash-classes-$PID"
$log = Join-Path ([IO.Path]::GetTempPath()) "s08-app-crash-$PID.log"
$errorLog = $log + ".err"
$database = "s08_app_crash_db_$PID"
$user = "s08_app_crash_$PID@example.com"
$password = "AppCrashPassword!2026A"
$backend = $null
$holder = $null
$jobs = @()
$saved = @{}
$names = @("S08_CRASH_ADMIN_URL", "S08_CRASH_DB_URL", "S08_CRASH_DB_USER", "S08_CRASH_DB_PASSWORD", "S08_CRASH_DB_NAME", "S08_CRASH_TABLE", "MYSQL_HOST", "MYSQL_PORT", "MYSQL_DATABASE", "MYSQL_USERNAME", "MYSQL_PASSWORD", "JWT_SECRET", "SPRING_PROFILES_ACTIVE")

function Invoke-Probe([string]$mode) {
    & $script:java -cp ($script:connector + ";" + $classes) S08CrashRecoveryProbe $mode
    if ($LASTEXITCODE -ne 0) { throw "Probe failed: $mode" }
}

function Api([string]$method, [string]$path, [object]$body, [hashtable]$headers = @{}) {
    $parameters = @{ UseBasicParsing = $true; Method = $method; Uri = "http://127.0.0.1:18082$path"; TimeoutSec = 15; Headers = $headers }
    if ($null -ne $body) { $parameters.ContentType = "application/json"; $parameters.Body = $body | ConvertTo-Json -Compress }
    $response = Invoke-WebRequest @parameters
    $text = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Data = if ($text) { $text | ConvertFrom-Json } else { $null } }
}

function Wait-Health() {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        if ($backend.HasExited) { throw "Backend exited before health" }
        try { $health = Invoke-RestMethod "http://127.0.0.1:18082/actuator/health" -TimeoutSec 3; if ($health.status -eq "UP" -and $health.components.db.status -eq "UP") { return } } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Backend health timeout"
}

try {
    foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }
    $javaHome = $env:JAVA_HOME
    $script:java = Join-Path $javaHome "bin\java.exe"
    $javac = Join-Path $javaHome "bin\javac.exe"
    $connectorRoot = Join-Path $env:USERPROFILE ".m2\repository\com\mysql\mysql-connector-j"
    $connector = Get-ChildItem $connectorRoot -Filter "mysql-connector-j-*.jar" -Recurse | Where-Object { $_.Name -notmatch "-(sources|javadoc)\.jar$" } | Sort-Object FullName -Descending | Select-Object -First 1
    if (-not (Test-Path $script:java) -or -not (Test-Path $javac) -or $null -eq $connector) { throw "Java or MySQL connector unavailable" }
    $script:connector = $connector.FullName
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    & $javac -cp $script:connector -d $classes (Join-Path $PSScriptRoot "S08CrashRecoveryProbe.java")
    if ($LASTEXITCODE -ne 0) { throw "Probe compilation failed" }

    $env:S08_CRASH_ADMIN_URL = "jdbc:mysql://127.0.0.1:3306/"
    $env:S08_CRASH_DB_USER = "root"
    if ([string]::IsNullOrWhiteSpace($env:S08_CRASH_DB_PASSWORD)) { throw "S08_CRASH_DB_PASSWORD is required" }
    $env:S08_CRASH_DB_NAME = $database
    $env:S08_CRASH_DB_URL = "jdbc:mysql://127.0.0.1:3306/$database"
    $env:S08_CRASH_TABLE = "unused"
    Invoke-Probe "create-database"
    $env:SPRING_PROFILES_ACTIVE = "local"; $env:MYSQL_HOST = "127.0.0.1"; $env:MYSQL_PORT = "3306"; $env:MYSQL_DATABASE = $database; $env:MYSQL_USERNAME = "root"; $env:MYSQL_PASSWORD = $env:S08_CRASH_DB_PASSWORD; $env:JWT_SECRET = ([guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N"))
    $jar = Join-Path $repoRoot "backend\target\backend-0.0.1-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) { throw "Backend jar is missing; run Maven package first" }
    $backend = Start-Process $script:java -ArgumentList @("-jar", $jar, "--server.port=18082") -WorkingDirectory $repoRoot -RedirectStandardOutput $log -RedirectStandardError $errorLog -PassThru
    Wait-Health
    $registered = Api "POST" "/api/v1/auth/register" @{ login = $user; password = $password; role = "USER" }
    if ($registered.Status -ne 200) { throw "Register failed" }
    $quote = Api "POST" "/api/v1/points/quotes" @{ planCount = 1 } @{ Authorization = "Bearer $($registered.Data.accessToken)"; "Idempotency-Key" = "app-crash-quote" }
    if ($quote.Status -ne 200) { throw "Quote failed" }
    $env:S08_CRASH_USER_ID = [string]$registered.Data.userId
    $holderLog = Join-Path ([IO.Path]::GetTempPath()) "s08-account-holder-$PID.log"
    $holder = Start-Process $script:java -ArgumentList @("-cp", ($script:connector + ";" + $classes), "S08CrashRecoveryProbe", "hold-account") -RedirectStandardOutput $holderLog -PassThru
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) { Start-Sleep 1; if ((Get-Content $holderLog -ErrorAction SilentlyContinue) -match "S08_ACCOUNT_LOCK_READY") { $ready = $true; break } }
    if (-not $ready) { throw "Account lock holder did not become ready" }
    $reserveBody = @{ quoteReference = $quote.Data.quoteReference } | ConvertTo-Json -Compress
    $reserveJob = { param($payload,$token); try { Invoke-WebRequest -UseBasicParsing -Method POST -Uri "http://127.0.0.1:18082/api/v1/points/reserve" -Headers @{ Authorization = "Bearer $token"; "Idempotency-Key" = "app-crash-reserve" } -ContentType "application/json" -Body $payload | Out-Null } catch { } }
    $jobs = @(Start-Job -ScriptBlock $reserveJob -ArgumentList $reserveBody,([string]$registered.Data.accessToken))
    Start-Sleep -Seconds 3
    if ($jobs[0].State -ne "Running") { throw "Reserve request did not remain blocked" }
    Stop-Process -Id $backend.Id -Force
    $backend = $null
    Stop-Process -Id $holder.Id -Force
    $holder = $null
    $jobs | Wait-Job -Timeout 10 | Out-Null
    $jobs | Remove-Job -Force
    $jobs = @()
    $backend = Start-Process $script:java -ArgumentList @("-jar", $jar, "--server.port=18082") -WorkingDirectory $repoRoot -RedirectStandardOutput $log -RedirectStandardError $errorLog -PassThru
    Wait-Health
    $state = & $script:java -cp ($script:connector + ";" + $classes) S08CrashRecoveryProbe account-state
    if ($state -notmatch "S08_APP_CRASH_STATE\|100\|0") { throw "Application crash recovery state mismatch: $state" }
    Write-Output "Application crash recovery passed: blocked reserve rolled back with balance 100 and reserve ledger 0"
}
catch {
    Write-Error $_
    exit 1
}
finally {
    if ($null -ne $backend -and -not $backend.HasExited) { Stop-Process -Id $backend.Id -Force }
    if ($null -ne $holder -and -not $holder.HasExited) { Stop-Process -Id $holder.Id -Force }
    foreach ($job in $jobs) { Remove-Job $job -Force -ErrorAction SilentlyContinue }
    try { & $script:java -cp ($script:connector + ";" + $classes) S08CrashRecoveryProbe drop-database | Out-Null } catch { }
    try { if (Test-Path $classes) { Remove-Item -LiteralPath $classes -Recurse -Force } } catch { }
    Remove-Item -LiteralPath $log,$errorLog,(Join-Path ([IO.Path]::GetTempPath()) "s08-account-holder-$PID.log") -Force -ErrorAction SilentlyContinue
    foreach ($name in $names) { if ($null -eq $saved[$name]) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue } else { Set-Item "Env:$name" $saved[$name] } }
}
