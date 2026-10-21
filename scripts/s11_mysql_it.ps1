param(
    [string]$MysqlHome = "D:\MySQL\mysql-8.4.8-winx64",
    [string]$JavaHome = "D:\Develope\Java工具\jdk"
)

$ErrorActionPreference = "Stop"
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$sandbox = Join-Path $tempRoot ("agenttravel-s11-mysql-" + [guid]::NewGuid().ToString("N"))
$dataDir = Join-Path $sandbox "data"
$logDir = Join-Path $sandbox "logs"
$mysqld = Join-Path $MysqlHome "bin\mysqld.exe"
$mysql = Join-Path $MysqlHome "bin\mysql.exe"
$mysqladmin = Join-Path $MysqlHome "bin\mysqladmin.exe"
$mysqldProcess = $null
$exitCode = 1
$baselineMysqlPids = @(Get-Process mysqld -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
$startedAt = Get-Date

if (-not (Test-Path -LiteralPath $mysqld -PathType Leaf)) {
    throw "找不到一次性数据库程序"
}

New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()

$database = "s11_" + [guid]::NewGuid().ToString("N").Substring(0, 12)
$databaseUser = "s11u_" + [guid]::NewGuid().ToString("N").Substring(0, 10)
$databasePassword = [guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")
$errorLog = Join-Path $logDir "mysqld-error.log"
$stdoutLog = Join-Path $logDir "mysqld-out.log"

try {
    & $mysqld --no-defaults --initialize-insecure --basedir="$MysqlHome" --datadir="$dataDir" --log-error="$errorLog" --console
    if ($LASTEXITCODE -ne 0) {
        throw "一次性数据库初始化失败"
    }

    $arguments = @(
        "--no-defaults",
        "--basedir=$MysqlHome",
        "--datadir=$dataDir",
        "--port=$port",
        "--bind-address=127.0.0.1",
        "--mysqlx=0",
        "--skip-log-bin",
        "--performance-schema=OFF",
        "--innodb-buffer-pool-size=64M",
        "--character-set-server=utf8mb4",
        "--collation-server=utf8mb4_0900_ai_ci",
        "--default-time-zone=+00:00",
        "--log-error=$errorLog"
    )
    $mysqldProcess = Start-Process -FilePath $mysqld -ArgumentList $arguments -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutLog -PassThru

    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = "SilentlyContinue"
        & $mysqladmin --protocol=TCP --host=127.0.0.1 --port=$port --user=root ping 2>&1 | Out-Null
        $pingExit = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorAction
        if ($pingExit -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) {
        throw "一次性数据库未在限定时间内就绪"
    }

    $setupSql = "CREATE DATABASE ``$database`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER '$databaseUser'@'%' IDENTIFIED BY '$databasePassword'; GRANT ALL PRIVILEGES ON ``$database``.* TO '$databaseUser'@'%'; FLUSH PRIVILEGES;"
    & $mysql --protocol=TCP --host=127.0.0.1 --port=$port --user=root --execute=$setupSql
    if ($LASTEXITCODE -ne 0) {
        throw "一次性数据库账号创建失败"
    }

    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    $env:S11_IT = "true"
    $env:DB_URL = "jdbc:mysql://127.0.0.1:$port/$database"
    $env:DB_USERNAME = $databaseUser
    $env:DB_PASSWORD = $databasePassword

    & mvn -o -f backend/pom.xml -Dtest=S11MySqlIntegrationTests test
    $exitCode = $LASTEXITCODE
}
catch {
    Write-Output $_.Exception.Message
    if (Test-Path -LiteralPath $errorLog) {
        Get-Content -LiteralPath $errorLog -Tail 40
    }
}
finally {
    if ($null -ne $mysqldProcess -and -not $mysqldProcess.HasExited) {
    & $mysqladmin --protocol=TCP --host=127.0.0.1 --port=$port --user=root shutdown 2>$null
        $mysqldProcess.WaitForExit(10000) | Out-Null
        if (-not $mysqldProcess.HasExited) {
            Stop-Process -Id $mysqldProcess.Id -Force
            $mysqldProcess.WaitForExit(5000) | Out-Null
        }
    }
    $ownedProcesses = Get-Process mysqld -ErrorAction SilentlyContinue | Where-Object {
        $baselineMysqlPids -notcontains $_.Id -and $_.StartTime -ge $startedAt
    }
    $ownedProcesses | Stop-Process -Force
    $resolvedSandbox = [IO.Path]::GetFullPath($sandbox)
    $insideTemp = $resolvedSandbox.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)
    $expectedName = [IO.Path]::GetFileName($resolvedSandbox).StartsWith("agenttravel-s11-mysql-")
    if ($insideTemp -and $expectedName -and (Test-Path -LiteralPath $resolvedSandbox)) {
        Remove-Item -LiteralPath $resolvedSandbox -Recurse -Force
    }
}

exit $exitCode
