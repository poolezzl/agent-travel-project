[CmdletBinding()]
param(
    [int]$MySqlPort = 0,
    [int]$BackendPort = 0,
    [string]$ProjectName = ""
)

$ErrorActionPreference = "Stop"
$onWindows = ($env:OS -eq "Windows_NT")
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot "deploy\docker-compose.s03.yml"
$composeProject = if ($ProjectName) { $ProjectName } else { "s03-validation-$PID" }
$backendProcess = $null
$backendLog = Join-Path ([IO.Path]::GetTempPath()) "s03-backend-$PID.log"
$backendErrorLog = Join-Path ([IO.Path]::GetTempPath()) "s03-backend-$PID.err.log"
$exitCode = 0
$composeStarted = $false
$savedEnvironment = @{}
$environmentNames = @(
    "SPRING_PROFILES_ACTIVE",
    "MYSQL_HOST",
    "MYSQL_PORT",
    "MYSQL_DATABASE",
    "MYSQL_USERNAME",
    "MYSQL_PASSWORD",
    "DB_URL",
    "DB_USERNAME",
    "DB_PASSWORD",
    "FLYWAY_ENABLED",
    "JWT_SECRET",
    "S03_MYSQL_ROOT_PASSWORD",
    "S03_MYSQL_DATABASE",
    "S03_MYSQL_USER",
    "S03_MYSQL_PASSWORD",
    "S03_MYSQL_PORT"
)

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $FilePath"
    }
}

function Get-FreeTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function New-TemporaryPassword {
    return "$([Guid]::NewGuid().ToString('N'))A9"
}

function New-TemporaryJwtSecret {
    # JwtTokenService 要求至少 32 个字符；这里为单次隔离校验生成临时密钥，
    # 不落盘、不复用，校验结束后随进程环境变量一起恢复。
    return "$([Guid]::NewGuid().ToString('N'))$([Guid]::NewGuid().ToString('N'))"
}

function Resolve-PublishedPort {
    # 以 compose 实际发布的端口为准，避免脚本假设的端口与 compose 解析结果不一致。
    $output = (& docker @($script:composeArguments + @("port", "mysql", "3306")) | Out-String)
    foreach ($line in ($output -split "`n")) {
        $trimmed = $line.Trim()
        if ($trimmed -match ':(\d+)$') {
            return [int]$Matches[1]
        }
    }
    return $null
}

function Resolve-ContainerAddress {
    $output = (& docker inspect --format "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}" $script:containerId | Out-String).Trim()
    if ($output) { return $output }
    return $null
}

function Test-TcpPort {
    param(
        [Parameter(Mandatory = $true)][string]$TargetHost,
        [Parameter(Mandatory = $true)][int]$TargetPort
    )

    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($TargetHost, $TargetPort)
        if (-not $task.Wait(3000)) {
            return $false
        }
        return $client.Connected
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Get-MySqlServerVersion {
    # 只做 TCP 端口探测无法确认对端确实是 MySQL（可能是残留端口占用），
    # 这里读取 MySQL 握手包首字节确认服务端身份。
    param(
        [Parameter(Mandatory = $true)][string]$TargetHost,
        [Parameter(Mandatory = $true)][int]$TargetPort
    )

    $client = [Net.Sockets.TcpClient]::new()
    try {
        $client.ReceiveTimeout = 5000
        $client.Connect($TargetHost, $TargetPort)
        $stream = $client.GetStream()
        $buffer = New-Object byte[] 64
        $read = $stream.Read($buffer, 0, 64)
        # MySQL 初始握手包：4 字节包头 + 1 字节协议版本（10）+ 版本号字符串
        if ($read -lt 6 -or $buffer[4] -ne 10) {
            return $null
        }
        $version = [Text.Encoding]::ASCII.GetString($buffer, 5, [Math]::Min(30, $read - 5))
        if ($version -match '^([0-9]+\.[0-9]+\.[0-9]+)') {
            return $Matches[1]
        }
        return ($version -split "`0")[0]
    }
    catch {
        return $null
    }
    finally {
        $client.Dispose()
    }
}

function Invoke-MySqlQuery {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $arguments = $script:composeArguments + @(
        "exec", "-T", "-e", "MYSQL_PWD=$env:S03_MYSQL_PASSWORD", "mysql",
        "mysql", "--batch", "--skip-column-names", "--protocol=TCP", "-h127.0.0.1",
        "-u$env:S03_MYSQL_USER", $env:S03_MYSQL_DATABASE, "-e", $Sql
    )
    $result = & docker @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL query failed with exit code $LASTEXITCODE"
    }
    return @($result | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ })
}

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if ($Actual -ne $Expected) {
        throw "$Message. Expected '$Expected', got '$Actual'"
    }
}

function Assert-Health {
    param([Parameter(Mandatory = $true)][int]$Port)

    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -Method Get -TimeoutSec 5
    Assert-Equal $health.status "UP" "Health status is not UP"
    Assert-Equal $health.components.backend.details.databaseMode "mysql" "Health databaseMode is not mysql"
    Assert-Equal $health.components.db.status "UP" "Health db.status is not UP"
    Write-Output "Health: HTTP 200, status=UP, databaseMode=mysql, db.status=UP"
}

function Get-ExpectedMigrationCount {
    # S04 起仓库已有正式 Flyway 迁移，启动后的库不再为空表；
    # 这里按仓库实际迁移文件数量校验 Flyway 执行结果。
    $migrationDir = Join-Path $repoRoot "backend\src\main\resources\db\migration"
    if (-not (Test-Path -LiteralPath $migrationDir)) {
        return 0
    }
    return @(Get-ChildItem -LiteralPath $migrationDir -Filter "V*.sql" -File).Count
}

function Get-BackendJar {
    # 若已有 spring-boot repackage 产出的可执行 jar，直接用它启动，避免 spring-boot:run 的重复构建。
    $targetDir = Join-Path $repoRoot "backend\target"
    if (-not (Test-Path -LiteralPath $targetDir)) {
        return $null
    }
    $jar = Get-ChildItem -LiteralPath $targetDir -Filter "backend-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*.original" } |
        Select-Object -First 1
    if ($jar) { return $jar }
    return $null
}

function Assert-MigrationState {
    param([Parameter(Mandatory = $true)][string]$Phase)

    $expected = $script:expectedMigrationCount
    $applied = [int](Invoke-MySqlQuery "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1;" | Select-Object -First 1)
    $failed = [int](Invoke-MySqlQuery "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0;" | Select-Object -First 1)
    Assert-Equal $failed 0 "$Phase has failed Flyway migrations"
    Assert-Equal $applied $expected "$Phase applied migration count does not match repository migrations"

    $tableNames = @(Invoke-MySqlQuery "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name;")
    if ($expected -eq 0) {
        Assert-Equal $tableNames.Count 1 "$Phase table count is not one"
        Assert-Equal $tableNames[0] "flyway_schema_history" "$Phase contains an unexpected table"
    }
    elseif ($tableNames.Count -lt 2) {
        throw "$Phase expected the migrated schema, but only found $($tableNames.Count) table(s)"
    }
    Write-Output "$Phase tables=$($tableNames.Count); flyway_applied=$applied; flyway_failed=$failed"
}

function Stop-Backend {
    if ($null -eq $script:backendProcess) {
        return
    }

    if (-not $script:backendProcess.HasExited) {
        $taskKill = Get-Command taskkill.exe -ErrorAction SilentlyContinue
        if ($taskKill) {
            & $taskKill.Source /PID $script:backendProcess.Id /T /F | Out-Null
        }
        else {
            Stop-Process -Id $script:backendProcess.Id -Force -ErrorAction SilentlyContinue
        }
    }
    if (-not $onWindows) {
        # spring-boot:run 会 fork 出应用 JVM，单独杀掉 Maven 进程会留下占用端口的孤儿进程。
        & pkill -f -- "--server.port=$BackendPort" | Out-Null
    }
    $script:backendProcess = $null
}

function Start-Backend {
    $arguments = @(
        "-f", (Join-Path $repoRoot "backend\pom.xml"),
        "spring-boot:run",
        "-Dspring-boot.run.profiles=local",
        "-Dspring-boot.run.arguments=--server.port=$BackendPort"
    )
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $maven) {
        $maven = Get-Command mvn -ErrorAction Stop
    }

    # 已有可执行 jar 时直接用 java -jar 启动：省去 spring-boot:run 的插件解析与重新编译，
    # 托管 CI 上启动耗时从分钟级降到秒级。
    $jar = Get-BackendJar
    if ($jar) {
        Write-Output "Starting backend from jar: $($jar.FullName)"
        $arguments = @("-jar", $jar.FullName, "--spring.profiles.active=local", "--server.port=$BackendPort")
    }
    else {
        Write-Output "Starting backend with spring-boot:run"
    }

    if ($onWindows) {
        $fileName = if ($jar) { "java" } else { $maven.Source }
        $startArguments = $arguments
        if ($jar) {
            $startArguments = @("-jar", $jar.FullName, "--spring.profiles.active=local", "--server.port=$BackendPort")
        }
        $script:backendProcess = Start-Process -FilePath $fileName -ArgumentList $startArguments `
            -WorkingDirectory $repoRoot -RedirectStandardOutput $backendLog `
            -RedirectStandardError $backendErrorLog -PassThru
        return
    }

    # Linux runner：不用 Start-Process 的输出重定向（PowerShell 7 在重定向子进程输出时
    # 会在退出阶段抛出 ObjectDisposedException 并吞掉真实错误），改为由 bash 重定向到文件。
    # 命令写入独立 .sh 文件后再执行：Start-Process 会把 ArgumentList 按空格拼接，
    # 直接传 bash -c "<命令>" 会被拆成多个参数，bash 只执行第一个词就退出。
    $quoted = ($arguments | ForEach-Object { "'" + ($_ -replace "'", "'\''") + "'" }) -join " "
    $launch = if ($jar) {
        "exec java $quoted > '$backendLog' 2> '$backendErrorLog'"
    }
    else {
        "exec '" + $maven.Source + "' $quoted > '$backendLog' 2> '$backendErrorLog'"
    }
    $script:launchScript = Join-Path ([IO.Path]::GetTempPath()) "s03-backend-$PID.sh"
    Set-Content -LiteralPath $script:launchScript -Value $launch -Encoding utf8
    $script:backendProcess = Start-Process -FilePath "bash" -ArgumentList @($script:launchScript) `
        -WorkingDirectory $repoRoot -PassThru
}

function Wait-ForBackend {
    $deadline = (Get-Date).AddSeconds(240)
    do {
        if ($script:backendProcess.HasExited) {
            $diagnostic = @()
            if (Test-Path -LiteralPath $backendLog) {
                $diagnostic += Get-Content -LiteralPath $backendLog -Tail 80
            }
            if (Test-Path -LiteralPath $backendErrorLog) {
                $diagnostic += Get-Content -LiteralPath $backendErrorLog -Tail 80
            }
            throw "Backend exited before becoming healthy. Last output:`n$($diagnostic -join [Environment]::NewLine)"
        }
        try {
            Assert-Health -Port $BackendPort
            return
        }
        catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)

    $diagnostic = @()
    if (Test-Path -LiteralPath $backendLog) {
        $diagnostic += Get-Content -LiteralPath $backendLog -Tail 60
    }
    if (Test-Path -LiteralPath $backendErrorLog) {
        $diagnostic += Get-Content -LiteralPath $backendErrorLog -Tail 60
    }
    throw "Backend did not become healthy within 240 seconds. Last output:`n$($diagnostic -join [Environment]::NewLine)"
}

try {
    if (-not (Test-Path -LiteralPath $composeFile)) {
        throw "Compose file not found: $composeFile"
    }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker CLI is required"
    }

    foreach ($name in $environmentNames) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
    }

    if ($MySqlPort -eq 0) {
        $configuredPort = [Environment]::GetEnvironmentVariable("S03_MYSQL_PORT", "Process")
        if ($configuredPort) {
            $MySqlPort = [int]$configuredPort
        }
        else {
            $MySqlPort = Get-FreeTcpPort
        }
    }
    if ($BackendPort -eq 0) { $BackendPort = Get-FreeTcpPort }
    $databaseName = [Environment]::GetEnvironmentVariable("S03_MYSQL_DATABASE", "Process")
    if (-not $databaseName) { $databaseName = "s03_empty_validation_$PID" }
    $validatorUser = [Environment]::GetEnvironmentVariable("S03_MYSQL_USER", "Process")
    if (-not $validatorUser) { $validatorUser = "s03_validator" }
    $rootPassword = [Environment]::GetEnvironmentVariable("S03_MYSQL_ROOT_PASSWORD", "Process")
    if (-not $rootPassword) { $rootPassword = New-TemporaryPassword }
    $validatorPassword = [Environment]::GetEnvironmentVariable("S03_MYSQL_PASSWORD", "Process")
    if (-not $validatorPassword) { $validatorPassword = New-TemporaryPassword }
    $env:S03_MYSQL_ROOT_PASSWORD = $rootPassword
    $env:S03_MYSQL_PASSWORD = $validatorPassword
    $env:S03_MYSQL_DATABASE = $databaseName
    $env:S03_MYSQL_USER = $validatorUser
    $env:S03_MYSQL_PORT = "$MySqlPort"
    $env:MYSQL_HOST = "127.0.0.1"
    $env:MYSQL_PORT = "$MySqlPort"
    $env:MYSQL_DATABASE = $databaseName
    $env:MYSQL_USERNAME = $validatorUser
    $env:MYSQL_PASSWORD = $env:S03_MYSQL_PASSWORD
    $env:DB_URL = "jdbc:mysql://127.0.0.1:$MySqlPort/$databaseName"
    $env:DB_USERNAME = $validatorUser
    $env:DB_PASSWORD = $env:S03_MYSQL_PASSWORD
    $env:FLYWAY_ENABLED = "true"
    $env:SPRING_PROFILES_ACTIVE = "local"
    $jwtSecret = [Environment]::GetEnvironmentVariable("JWT_SECRET", "Process")
    if (-not $jwtSecret) { $jwtSecret = New-TemporaryJwtSecret }
    $env:JWT_SECRET = $jwtSecret

    $script:composeArguments = @("compose", "-p", $composeProject, "-f", $composeFile)
    $script:expectedMigrationCount = Get-ExpectedMigrationCount
    Write-Output "Repository Flyway migrations: $script:expectedMigrationCount"
    $composeStarted = $true
    Invoke-Checked "docker" ($script:composeArguments + @("up", "-d"))

    $containerId = (& docker @($script:composeArguments + @("ps", "-q", "mysql"))).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
        throw "Could not resolve the MySQL container"
    }
    $script:containerId = $containerId
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $healthStatus = (& docker inspect --format "{{.State.Health.Status}}" $containerId).Trim()
        if ($healthStatus -eq "healthy") { break }
        if ($healthStatus -eq "unhealthy") { throw "MySQL container became unhealthy" }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    Assert-Equal $healthStatus "healthy" "MySQL container did not become healthy"

    # 选择后端可用的 MySQL 端点：以 MySQL 握手确认，避免把“端口可连”误判为“数据库可用”。
    $publishedPort = Resolve-PublishedPort
    $containerAddress = Resolve-ContainerAddress
    if ($publishedPort) {
        Write-Output "Published MySQL port: $publishedPort"
    }
    Write-Output "MySQL container address: $containerAddress"

    $candidates = @()
    if ($onWindows) {
        # Docker Desktop 下容器 IP 不可达，优先使用 compose 发布的宿主端口。
        $candidates += , @("127.0.0.1", $publishedPort)
        $candidates += , @($containerAddress, 3306)
    }
    else {
        # Linux runner 上优先直连容器 IP，避免宿主发布端口被占用或不可达。
        $candidates += , @($containerAddress, 3306)
        $candidates += , @("127.0.0.1", $publishedPort)
    }

    $mysqlHost = $null
    $mysqlPortForApp = $null
    foreach ($candidate in $candidates) {
        if (-not $candidate[0] -or -not $candidate[1]) { continue }
        $version = Get-MySqlServerVersion -TargetHost $candidate[0] -TargetPort $candidate[1]
        if ($version) {
            $mysqlHost = $candidate[0]
            $mysqlPortForApp = $candidate[1]
            Write-Output "MySQL endpoint selected: ${mysqlHost}:${mysqlPortForApp} (server $version)"
            break
        }
        Write-Output "MySQL handshake failed on $($candidate[0]):$($candidate[1])"
    }
    if (-not $mysqlHost) {
        throw "MySQL handshake failed on all candidate endpoints"
    }
    $env:MYSQL_HOST = $mysqlHost
    $env:MYSQL_PORT = "$mysqlPortForApp"
    $env:DB_URL = "jdbc:mysql://${mysqlHost}:${mysqlPortForApp}/$databaseName"
    Write-Output "Backend datasource: MYSQL_HOST=$env:MYSQL_HOST MYSQL_PORT=$env:MYSQL_PORT DB_URL=$env:DB_URL"

    $initialTableCount = [int](Invoke-MySqlQuery "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();" | Select-Object -First 1)
    Assert-Equal $initialTableCount 0 "Initial database is not empty"
    $databaseSettings = Invoke-MySqlQuery "SELECT CONCAT(@@character_set_database, '|', @@collation_database);" | Select-Object -First 1
    Assert-Equal $databaseSettings "utf8mb4|utf8mb4_0900_ai_ci" "Database charset or collation is unexpected"
    Write-Output "Initial database: tables=$initialTableCount, charset=utf8mb4, collation=utf8mb4_0900_ai_ci"

    Start-Backend
    Wait-ForBackend
    Assert-MigrationState "First startup"
    Stop-Backend

    Start-Backend
    Wait-ForBackend
    Assert-MigrationState "Repeat startup"
    Stop-Backend

    Write-Output "S03 D-04 checks completed successfully for this isolated run; independent acceptance and the S03 Gate remain pending."
}
catch {
    $exitCode = 1
    Write-Error $_
    # 失败诊断：把容器发布端口与状态一并输出，便于定位环境差异。
    if ($composeStarted) {
        try {
            Write-Output "---- diagnostics: docker compose ps ----"
            & docker @($script:composeArguments + @("ps"))
            Write-Output "---- diagnostics: docker port mysql 3306 ----"
            & docker @($script:composeArguments + @("port", "mysql", "3306"))
            Write-Output "---- diagnostics: container address ----"
            & docker inspect --format "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}" $script:containerId
        }
        catch {
            Write-Output "diagnostics failed: $($_.Exception.Message)"
        }
    }
}
finally {
    Stop-Backend
    if ($composeStarted) {
        try {
            Invoke-Checked "docker" ($script:composeArguments + @("down", "-v", "--remove-orphans"))
        }
        catch {
            $exitCode = 1
            Write-Error $_
        }
    }

    foreach ($name in $environmentNames) {
        $value = $savedEnvironment[$name]
        if ($null -eq $value) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        }
        else {
            Set-Item "Env:$name" $value
        }
    }
    Remove-Item -LiteralPath $backendLog, $backendErrorLog -Force -ErrorAction SilentlyContinue
    if ($script:launchScript) {
        Remove-Item -LiteralPath $script:launchScript -Force -ErrorAction SilentlyContinue
    }
}

if ($exitCode -ne 0) {
    exit $exitCode
}
