[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [int]$HealthTimeoutSeconds = 90,
    [string]$JavaHome = 'D:\Java JDK\jdk-21.0.12+8'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_delivery.ps1')

$context = Initialize-LinkVerseDeliveryEnvironment -JavaHome $JavaHome
Assert-LinkVerseCommand -Name 'docker'

& (Join-Path $PSScriptRoot 'bootstrap.ps1')

if (-not $SkipBuild) {
    Write-Host '正在构建四个 Java 服务……'
    Push-Location $context.BackendRoot
    try {
        & (Join-Path $context.BackendRoot 'mvnw.cmd') '-DskipTests' package
        if ($LASTEXITCODE -ne 0) {
            throw "Java 服务构建失败，退出码：$LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

New-Item -ItemType Directory -Path $context.RuntimeRoot -Force | Out-Null
New-Item -ItemType Directory -Path $context.LogRoot -Force | Out-Null
$pidFile = Join-Path $context.RuntimeRoot 'services.json'
if (Test-Path -LiteralPath $pidFile) {
    $running = @((Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json) | Where-Object {
        $null -ne (Get-Process -Id $_.pid -ErrorAction SilentlyContinue)
    })
    if ($running.Count -gt 0) {
        throw '检测到由 start.ps1 启动的 Java 服务仍在运行，请先执行 scripts/clean.ps1。'
    }
}

$services = @(
    @{ Name = 'linkverse-identity'; Port = 18081 },
    @{ Name = 'linkverse-payment'; Port = 18083 },
    @{ Name = 'linkverse-trade'; Port = 18082 },
    @{ Name = 'linkverse-gateway'; Port = 18080 }
)
$started = [System.Collections.Generic.List[object]]::new()
try {
    foreach ($service in $services) {
        $jar = Join-Path $context.BackendRoot "$($service.Name)/target/$($service.Name)-0.1.0-SNAPSHOT.jar"
        if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
            throw "服务包不存在：$jar"
        }
        $stdout = Join-Path $context.LogRoot "$($service.Name).out.log"
        $stderr = Join-Path $context.LogRoot "$($service.Name).error.log"
        $process = Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') `
            -ArgumentList @('-jar', $jar, '--spring.profiles.active=local') `
            -WorkingDirectory $context.BackendRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $started.Add([PSCustomObject]@{
            name = $service.Name; pid = $process.Id; port = $service.Port; jar = $jar
        })
        $started | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $pidFile -Encoding utf8
        Wait-LinkVerseHealth -Service $service.Name -Port $service.Port `
            -TimeoutSeconds $HealthTimeoutSeconds
        Write-Host "服务已就绪：$($service.Name)"
    }
}
catch {
    foreach ($service in $started) {
        Stop-Process -Id $service.pid -Force -ErrorAction SilentlyContinue
    }
    throw
}

Write-Host 'LinkVerse 本地中间件与四个 Java 服务均已启动。'
