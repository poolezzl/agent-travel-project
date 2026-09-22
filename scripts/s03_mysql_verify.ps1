[CmdletBinding()]
param(
    [int]$MySqlPort = 0,
    [int]$BackendPort = 0,
    [string]$ProjectName = ""
)

$ErrorActionPreference = "Stop"
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

function Assert-EmptyDatabase {
    param([Parameter(Mandatory = $true)][string]$Phase)

    $tableNames = @(Invoke-MySqlQuery "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name;")
    Assert-Equal $tableNames.Count 1 "$Phase table count is not one"
    Assert-Equal $tableNames[0] "flyway_schema_history" "$Phase contains an unexpected table"

    $flywayRows = [int](Invoke-MySqlQuery "SELECT COUNT(*) FROM flyway_schema_history;" | Select-Object -First 1)
    Assert-Equal $flywayRows 0 "$Phase Flyway history is not empty"
    Write-Output "$Phase tables: $($tableNames -join ', '); flyway_rows=$flywayRows"
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
    $script:backendProcess = $null
}

function Start-Backend {
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $maven) {
        $maven = Get-Command mvn -ErrorAction Stop
    }

    $arguments = @(
        "-f", (Join-Path $repoRoot "backend\pom.xml"),
        "spring-boot:run",
        "-Dspring-boot.run.profiles=local",
        "-Dspring-boot.run.arguments=--server.port=$BackendPort"
    )
    $script:backendProcess = Start-Process -FilePath $maven.Source -ArgumentList $arguments `
        -WorkingDirectory $repoRoot -RedirectStandardOutput $backendLog `
        -RedirectStandardError $backendErrorLog -PassThru
}

function Wait-ForBackend {
    $deadline = (Get-Date).AddSeconds(90)
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

    throw "Backend did not become healthy within 90 seconds"
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

    $script:composeArguments = @("compose", "-p", $composeProject, "-f", $composeFile)
    $composeStarted = $true
    Invoke-Checked "docker" ($script:composeArguments + @("up", "-d"))

    $containerId = (& docker @($script:composeArguments + @("ps", "-q", "mysql"))).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
        throw "Could not resolve the MySQL container"
    }
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $healthStatus = (& docker inspect --format "{{.State.Health.Status}}" $containerId).Trim()
        if ($healthStatus -eq "healthy") { break }
        if ($healthStatus -eq "unhealthy") { throw "MySQL container became unhealthy" }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    Assert-Equal $healthStatus "healthy" "MySQL container did not become healthy"

    $initialTableCount = [int](Invoke-MySqlQuery "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();" | Select-Object -First 1)
    Assert-Equal $initialTableCount 0 "Initial database is not empty"
    $databaseSettings = Invoke-MySqlQuery "SELECT CONCAT(@@character_set_database, '|', @@collation_database);" | Select-Object -First 1
    Assert-Equal $databaseSettings "utf8mb4|utf8mb4_0900_ai_ci" "Database charset or collation is unexpected"
    Write-Output "Initial database: tables=$initialTableCount, charset=utf8mb4, collation=utf8mb4_0900_ai_ci"

    Start-Backend
    Wait-ForBackend
    Assert-EmptyDatabase "First startup"
    Stop-Backend

    Start-Backend
    Wait-ForBackend
    Assert-EmptyDatabase "Repeat startup"
    Stop-Backend

    Write-Output "S03 D-04 checks completed successfully for this isolated run; independent acceptance and the S03 Gate remain pending."
}
catch {
    $exitCode = 1
    Write-Error $_
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
}

if ($exitCode -ne 0) {
    exit $exitCode
}
