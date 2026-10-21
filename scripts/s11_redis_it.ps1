param(
    [Parameter(Mandatory = $true)]
    [string]$RedisArchive,
    [string]$JavaHome = "D:\Develope\Java工具\jdk"
)

$ErrorActionPreference = "Stop"
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$runtimeDir = Join-Path $tempRoot ("agenttravel-s11-redis-run-" + [guid]::NewGuid().ToString("N"))
$dataDir = Join-Path $runtimeDir "data"
$binDir = Join-Path $runtimeDir "bin"
$stdoutLog = Join-Path $runtimeDir "redis-out.log"
$stderrLog = Join-Path $runtimeDir "redis-error.log"
$redisProcess = $null
$exitCode = 1

if (-not (Test-Path -LiteralPath $RedisArchive -PathType Leaf)) {
    throw "找不到临时 Redis 压缩包"
}

New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
New-Item -ItemType Directory -Path $binDir -Force | Out-Null
Expand-Archive -LiteralPath $RedisArchive -DestinationPath $binDir -Force
$redisServer = Join-Path $binDir "redis-server.exe"
$redisCli = Join-Path $binDir "redis-cli.exe"
if (-not (Test-Path -LiteralPath $redisServer -PathType Leaf)) {
    throw "临时 Redis 服务程序不存在"
}

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()

try {
    $arguments = @(
        "--port", "$port",
        "--bind", "127.0.0.1",
        "--dir", $dataDir,
        "--save", "",
        "--appendonly", "no"
    )
    $redisProcess = Start-Process -FilePath $redisServer -ArgumentList $arguments -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = "SilentlyContinue"
        & $redisCli -h 127.0.0.1 -p $port ping 2>&1 | Out-Null
        $pingExit = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorAction
        if ($pingExit -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) {
        throw "临时 Redis 未就绪"
    }

    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    $env:S11_REDIS_IT = "true"
    $env:S11_REDIS_PORT = "$port"
    & mvn -o -f backend/pom.xml -Dtest=S11RedisIntegrationTests test
    $exitCode = $LASTEXITCODE
}
catch {
    Write-Output $_.Exception.Message
    if (Test-Path -LiteralPath $stderrLog) {
        Get-Content -LiteralPath $stderrLog -Tail 30
    }
}
finally {
    if ($null -ne $redisProcess -and -not $redisProcess.HasExited) {
        & $redisCli -h 127.0.0.1 -p $port -n 15 shutdown nosave 2>$null
        $redisProcess.WaitForExit(5000) | Out-Null
        if (-not $redisProcess.HasExited) {
            Stop-Process -Id $redisProcess.Id -Force
            $redisProcess.WaitForExit(3000) | Out-Null
        }
    }
    $resolvedRuntime = [IO.Path]::GetFullPath($runtimeDir)
    $insideTemp = $resolvedRuntime.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)
    $expectedName = [IO.Path]::GetFileName($resolvedRuntime).StartsWith("agenttravel-s11-redis-run-")
    if ($insideTemp -and $expectedName -and (Test-Path -LiteralPath $resolvedRuntime)) {
        Remove-Item -LiteralPath $resolvedRuntime -Recurse -Force
    }
}

exit $exitCode
