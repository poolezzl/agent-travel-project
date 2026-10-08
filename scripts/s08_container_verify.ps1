[CmdletBinding()]
param(
    [string]$ProjectName = "",
    [int]$MySqlPort = 0,
    [int]$BackendPort = 0
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot "deploy\docker-compose.s03.yml"
$project = if ($ProjectName) { $ProjectName } else { "s08-container-$PID" }
$backend = $null
$composeStarted = $false
$log = Join-Path ([IO.Path]::GetTempPath()) "s08-backend-$PID.log"
$errorLog = $log + ".err"
$saved = @{}
$names = @("SPRING_PROFILES_ACTIVE", "MYSQL_HOST", "MYSQL_PORT", "MYSQL_DATABASE", "MYSQL_USERNAME", "MYSQL_PASSWORD", "JWT_SECRET", "S03_MYSQL_ROOT_PASSWORD", "S03_MYSQL_DATABASE", "S03_MYSQL_USER", "S03_MYSQL_PASSWORD", "S03_MYSQL_PORT")

function Port() {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return ([Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
}

function Check([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }

function Compose([string[]]$arguments) {
    & docker compose -p $project -f $composeFile @arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($arguments -join ' ')" }
}

function Sql([string]$query) {
    $result = & docker compose -p $project -f $composeFile exec -T -e "MYSQL_PWD=$env:S03_MYSQL_ROOT_PASSWORD" mysql mysql --batch --skip-column-names --protocol=TCP --host=127.0.0.1 --user=root --database=$env:S03_MYSQL_DATABASE --execute=$query 2>&1
    if ($LASTEXITCODE -ne 0) { throw "mysql query failed: $($result -join [Environment]::NewLine)" }
    return @($result | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ })
}

function Api([string]$method, [string]$path, [object]$body, [hashtable]$headers = @{}) {
    $parameters = @{ UseBasicParsing = $true; Method = $method; Uri = "http://127.0.0.1:$BackendPort$path"; TimeoutSec = 15; Headers = $headers }
    if ($null -ne $body) { $parameters.ContentType = "application/json"; $parameters.Body = $body | ConvertTo-Json -Compress }
    try {
        $response = Invoke-WebRequest @parameters
        $text = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Data = if ($text) { $text | ConvertFrom-Json } else { $null } }
    }
    catch {
        $response = $_.Exception.Response
        if ($null -eq $response) { throw }
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        $text = $reader.ReadToEnd(); $reader.Dispose()
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Data = if ($text) { $text | ConvertFrom-Json } else { $null } }
    }
}

function Stop-Backend() {
    if ($null -ne $script:backend -and -not $script:backend.HasExited) { Stop-Process -Id $script:backend.Id -Force }
    $script:backend = $null
}

function Wait-Health() {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        if ($backend.HasExited) { throw "backend exited before health" }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$BackendPort/actuator/health" -TimeoutSec 3
            if ($health.status -eq "UP" -and $health.components.db.status -eq "UP") { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "backend health timeout"
}

function Start-Backend() {
    $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
    $script:backend = Start-Process $maven -ArgumentList @("-f", (Join-Path $repoRoot "backend\pom.xml"), "spring-boot:run", "-Dspring-boot.run.profiles=local", "-Dspring-boot.run.arguments=--server.port=$BackendPort") -WorkingDirectory $repoRoot -RedirectStandardOutput $log -RedirectStandardError $errorLog -PassThru
    Wait-Health
}

try {
    foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }
    if ($MySqlPort -eq 0) { $MySqlPort = Port }
    if ($BackendPort -eq 0) { $BackendPort = Port }
    $database = "s08_container_$PID"
    $validator = "s08_validator_$PID"
    $rootPassword = ([Guid]::NewGuid().ToString("N") + "A9")
    $validatorPassword = ([Guid]::NewGuid().ToString("N") + "B8")
    $jwt = [Guid]::NewGuid().ToString("N") + [Guid]::NewGuid().ToString("N")
    $env:S03_MYSQL_ROOT_PASSWORD = $rootPassword; $env:S03_MYSQL_PASSWORD = $validatorPassword; $env:S03_MYSQL_DATABASE = $database; $env:S03_MYSQL_USER = $validator; $env:S03_MYSQL_PORT = "$MySqlPort"
    $env:SPRING_PROFILES_ACTIVE = "local"; $env:MYSQL_HOST = "127.0.0.1"; $env:MYSQL_PORT = "$MySqlPort"; $env:MYSQL_DATABASE = $database; $env:MYSQL_USERNAME = $validator; $env:MYSQL_PASSWORD = $validatorPassword; $env:JWT_SECRET = $jwt
    Compose @("up", "-d")
    $composeStarted = $true
    $container = (& docker compose -p $project -f $composeFile ps -q mysql).Trim()
    Check (-not [string]::IsNullOrWhiteSpace($container)) "mysql container not found"
    $deadline = (Get-Date).AddSeconds(120)
    do { $healthState = (& docker inspect --format "{{.State.Health.Status}}" $container).Trim(); if ($healthState -eq "healthy") { break }; if ($healthState -eq "unhealthy") { throw "mysql container unhealthy" }; Start-Sleep 2 } while ((Get-Date) -lt $deadline)
    Check ($healthState -eq "healthy") "mysql container health timeout"
    $env:DB_URL = "jdbc:mysql://127.0.0.1:$MySqlPort/$database"; $env:DB_USERNAME = $validator; $env:DB_PASSWORD = $validatorPassword
    Start-Backend
    $tables = [int](Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();" | Select-Object -First 1)
    $flyway = Sql "SELECT COUNT(*),COALESCE(MAX(version),'') FROM flyway_schema_history;" | Select-Object -First 1
    Check ($tables -eq 41 -and $flyway -match "1`t1|1\s+1") "migration metadata mismatch"
    $login = "s08_container_$PID@example.com"; $password = "ContainerPassword!2026A"
    $register = Api "POST" "/api/v1/auth/register" @{ login = $login; password = $password; role = "USER" }
    Check ($register.Status -eq 200) "register failed"
    $token = [string]$register.Data.accessToken
    $balance = Api "GET" "/api/v1/points/balance" $null @{ Authorization = "Bearer $token" }
    Check ($balance.Status -eq 200 -and $balance.Data.balancePoints -eq 100) "registration points failed"
    $quote = Api "POST" "/api/v1/points/quotes" @{ planCount = 1 } @{ Authorization = "Bearer $token"; "Idempotency-Key" = "s08-container-quote" }
    $reserve = Api "POST" "/api/v1/points/reserve" @{ quoteReference = $quote.Data.quoteReference } @{ Authorization = "Bearer $token"; "Idempotency-Key" = "s08-container-reserve" }
    $refund = Api "POST" "/api/v1/points/refund" @{ reservationReference = $reserve.Data.businessReference } @{ Authorization = "Bearer $token"; "Idempotency-Key" = "s08-container-refund" }
    Check ($quote.Status -eq 200 -and $quote.Data.points -eq 10 -and $reserve.Status -eq 200 -and $refund.Status -eq 200) "points flow failed"
    $confirmQuote = Api "POST" "/api/v1/points/quotes" @{ planCount = 1 } @{ Authorization = "Bearer $token"; "Idempotency-Key" = "s08-container-confirm-quote" }
    $confirmReserve = Api "POST" "/api/v1/points/reserve" @{ quoteReference = $confirmQuote.Data.quoteReference } @{ Authorization = "Bearer $token"; "Idempotency-Key" = "s08-container-confirm-reserve" }
    $confirmFirst = Api "POST" "/api/v1/points/confirm" @{ reservationReference = $confirmReserve.Data.businessReference } @{ Authorization = "Bearer $token"; "Idempotency-Key" = "s08-container-confirm-1" }
    $confirmSecond = Api "POST" "/api/v1/points/confirm" @{ reservationReference = $confirmReserve.Data.businessReference } @{ Authorization = "Bearer $token"; "Idempotency-Key" = "s08-container-confirm-2" }
    $confirmBalance = Api "GET" "/api/v1/points/balance" $null @{ Authorization = "Bearer $token" }
    Check ($confirmFirst.Status -eq 200 -and $confirmFirst.Data.pointsDelta -eq 0 -and $confirmSecond.Status -eq 200 -and $confirmSecond.Data.reused -eq $true -and $confirmBalance.Data.balancePoints -eq 90) "confirm idempotency failed"
    Stop-Backend
    Start-Backend
    $repeatTables = [int](Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();" | Select-Object -First 1)
    Check ($repeatTables -eq 41) "repeat startup table count mismatch"
    $promote = "UPDATE user_account SET role='ADMIN' WHERE email='$login';"
    Sql $promote | Out-Null
    $admin = Api "POST" "/api/v1/auth/login" @{ login = $login; password = $password }
    Check ($admin.Status -eq 200 -and $admin.Data.role -eq "ADMIN") "admin login failed"
    $grantBody = @{ userId = $register.Data.userId; points = 7 } | ConvertTo-Json -Compress
    $grantJob = { param($url, $access, $payload); try { $response = Invoke-WebRequest -UseBasicParsing -Method POST -Uri $url -Headers @{ Authorization = "Bearer $access"; "Idempotency-Key" = "s08-container-grant" } -ContentType "application/json" -Body $payload; [int]$response.StatusCode } catch { 0 } }
    $jobs = 1..2 | ForEach-Object { Start-Job -ScriptBlock $grantJob -ArgumentList @("http://127.0.0.1:$BackendPort/api/v1/points/grant", $admin.Data.accessToken, $grantBody) }
    $jobs | Wait-Job -Timeout 30 | Out-Null
    $grantStatuses = @($jobs | Receive-Job)
    $jobs | Remove-Job -Force
    $afterGrant = Api "GET" "/api/v1/points/balance" $null @{ Authorization = "Bearer $token" }
    Check ($grantStatuses.Count -eq 2 -and @($grantStatuses | Where-Object { $_ -eq 200 }).Count -eq 2 -and $afterGrant.Data.balancePoints -eq 97) "concurrent grant failed"
    $lockLogin = "s08_lock_$PID@example.com"; $lockPassword = "LockPassword!2026A"
    $lockRegister = Api "POST" "/api/v1/auth/register" @{ login = $lockLogin; password = $lockPassword; role = "USER" }
    $wrong = @(1..5 | ForEach-Object { Api "POST" "/api/v1/auth/login" @{ login = $lockLogin; password = "WrongPassword!2026A" } })
    Check ($lockRegister.Status -eq 200 -and @($wrong | Select-Object -First 4 | Where-Object { $_.Status -eq 401 }).Count -eq 4 -and $wrong[4].Status -eq 423) "lock flow failed"
    Write-Output "container checks passed: migration=1, repeat-start=1, auth=2, points=1, confirm-idempotency=1, concurrency=1, cleanup=1"
}
catch {
    Write-Error $_
    exit 1
}
finally {
    Stop-Backend
    if ($composeStarted) { try { Compose @("down", "-v", "--remove-orphans") } catch { } }
    foreach ($name in $names) { if ($null -eq $saved[$name]) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue } else { Set-Item "Env:$name" $saved[$name] } }
    Remove-Item -LiteralPath $log, $errorLog -Force -ErrorAction SilentlyContinue
}
