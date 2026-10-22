[CmdletBinding()]
param(
    [string]$Pom = "backend/pom.xml",
    [ValidateSet("compile", "test", "verify", "package")]
    [string]$Goal = "test",
    [string]$Test
)

$ErrorActionPreference = "Continue"

$candidates = @()
if ($env:JAVA21_HOME) {
    $candidates += $env:JAVA21_HOME
}
$candidates += "D:\Develope\Java工具\jdk"
$candidates += Get-ChildItem -Path "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "jdk-21*" } |
    Select-Object -ExpandProperty FullName
$candidates += Get-ChildItem -Path "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "jdk-21*" } |
    Select-Object -ExpandProperty FullName

$javaHome = $null
foreach ($candidate in $candidates) {
    if (-not $candidate) {
        continue
    }
    $java = Join-Path $candidate "bin\java.exe"
    if (-not (Test-Path -LiteralPath $java)) {
        continue
    }
    $versionLine = (& $java -version 2>&1 | Select-Object -First 1).ToString()
    if ($versionLine -match 'version "(?<major>\d+)' -and $Matches.major -eq "21") {
        $javaHome = $candidate
        break
    }
}

if (-not $javaHome) {
    throw "未找到 Java 21，请设置 JAVA21_HOME 指向 Java 21 安装目录。"
}

$env:JAVA_HOME = $javaHome
& (Join-Path $javaHome "bin\java.exe") -version

$maven = Get-Command mvn -ErrorAction Stop
$mavenArgs = @("-f", $Pom)
if ($Test) {
    $mavenArgs += "-Dtest=$Test"
}
$mavenArgs += $Goal
& $maven.Source @mavenArgs
exit $LASTEXITCODE
