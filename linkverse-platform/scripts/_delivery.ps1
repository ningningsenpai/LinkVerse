Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '_infrastructure.ps1')

function Get-LinkVerseDeliveryContext {
    $infrastructure = Get-LinkVerseInfrastructureContext
    [PSCustomObject]@{
        PlatformRoot       = $infrastructure.PlatformRoot
        BackendRoot        = Join-Path $infrastructure.PlatformRoot 'backend'
        RuntimeRoot        = Join-Path $infrastructure.PlatformRoot '.runtime'
        LogRoot            = Join-Path $infrastructure.PlatformRoot 'logs'
        TestResultRoot     = Join-Path $infrastructure.PlatformRoot 'test-results'
        Infrastructure    = $infrastructure
    }
}

function Initialize-LinkVerseDeliveryEnvironment {
    param(
        [string]$JavaHome = 'D:\Java JDK\jdk-21.0.12+8'
    )

    $context = Get-LinkVerseDeliveryContext
    if (-not (Test-Path -LiteralPath $context.Infrastructure.EnvironmentFile -PathType Leaf)) {
        throw '缺少 infrastructure/.env，请先执行 scripts/prepare-local.ps1。'
    }
    Import-LinkVerseInfrastructureEnvironment -Context $context.Infrastructure `
        -EnvironmentFile $context.Infrastructure.EnvironmentFile
    Test-LinkVerseInfrastructureSecrets

    $javaExecutable = Join-Path $JavaHome 'bin/java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        throw "指定的 JDK 21 不存在：$JavaHome"
    }
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $JavaHome, 'Process')
    [Environment]::SetEnvironmentVariable('Path', "$JavaHome\bin;$env:Path", 'Process')
    [Environment]::SetEnvironmentVariable('SPRING_PROFILES_ACTIVE', 'local', 'Process')
    [Environment]::SetEnvironmentVariable('NACOS_SERVER_ADDR',
        "127.0.0.1:$((Get-LinkVerseEnvironmentValue -Name 'NACOS_SERVER_HOST_PORT'))", 'Process')
    [Environment]::SetEnvironmentVariable('REDIS_PORT',
        (Get-LinkVerseEnvironmentValue -Name 'REDIS_HOST_PORT'), 'Process')
    [Environment]::SetEnvironmentVariable('RABBITMQ_PORT',
        (Get-LinkVerseEnvironmentValue -Name 'RABBITMQ_AMQP_HOST_PORT'), 'Process')
    return $context
}

function Invoke-LinkVerseJsonRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 15
    )

    $parameters = @{
        Method              = $Method
        Uri                 = $Uri
        Headers             = $Headers
        TimeoutSec          = $TimeoutSec
        SkipHttpErrorCheck  = $true
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    $response = Invoke-WebRequest @parameters
    $content = if ($response.Content -is [byte[]]) {
        [Text.Encoding]::UTF8.GetString($response.Content)
    }
    else {
        [string]$response.Content
    }
    $json = $null
    if (-not [string]::IsNullOrWhiteSpace($content)) {
        try {
            $json = $content | ConvertFrom-Json
        }
        catch {
            $json = $null
        }
    }
    [PSCustomObject]@{
        StatusCode = [int]$response.StatusCode
        Json       = $json
        Content    = $content
        Headers    = $response.Headers
    }
}

function Assert-LinkVerseStatus {
    param(
        [Parameter(Mandatory = $true)][object]$Response,
        [Parameter(Mandatory = $true)][int[]]$Expected,
        [Parameter(Mandatory = $true)][string]$Operation
    )
    if ($Response.StatusCode -notin $Expected) {
        throw "$Operation 失败，HTTP 状态码为 $($Response.StatusCode)。"
    }
}

function Wait-LinkVerseHealth {
    param(
        [Parameter(Mandatory = $true)][string]$Service,
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutSeconds = 90
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-LinkVerseJsonRequest -Method Get `
                -Uri "http://127.0.0.1:$Port/actuator/health/liveness" -TimeoutSec 3
            if ($response.StatusCode -eq 200 -and $response.Json.status -eq 'UP') {
                return
            }
        }
        catch {
            # 服务启动期间连接失败是预期状态，直到超时才报告错误。
        }
        Start-Sleep -Milliseconds 500
    }
    throw "服务未在限定时间内就绪：$Service"
}

function Get-LinkVerseSeedStatePath {
    Join-Path (Get-LinkVerseDeliveryContext).RuntimeRoot 'seed-state.json'
}

function Invoke-LinkVerseTradeSql {
    param(
        [Parameter(Mandatory = $true)][object]$Context,
        [Parameter(Mandatory = $true)][string]$Sql
    )

    $command = 'MYSQL_PWD="$TRADE_APP_PASSWORD" mysql --protocol=socket --user="$TRADE_APP_USER" ' +
        '--database="$TRADE_DB_NAME" --batch --skip-column-names --execute="$1"'
    Invoke-LinkVerseCompose -Context $Context.Infrastructure `
        -EnvironmentFile $Context.Infrastructure.EnvironmentFile `
        -ComposeArguments @(
            'exec', '--no-TTY', 'mysql', 'sh', '-ec', $command, 'linkverse-trade-sql', $Sql
        )
}

function Invoke-LinkVerseRedisCli {
    param(
        [Parameter(Mandatory = $true)][object]$Context,
        [Parameter(Mandatory = $true)][string[]]$RedisArguments
    )

    $command = 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning "$@"'
    $composeArguments = @(
        'exec', '--no-TTY', 'redis', 'sh', '-ec', $command, 'linkverse-redis-cli'
    ) + $RedisArguments
    Invoke-LinkVerseCompose -Context $Context.Infrastructure `
        -EnvironmentFile $Context.Infrastructure.EnvironmentFile `
        -ComposeArguments $composeArguments
}
