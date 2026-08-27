[CmdletBinding()]
param(
    [switch]$StaticOnly,
    [switch]$RequireImageDigests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '_infrastructure.ps1')

$context = Get-LinkVerseInfrastructureContext
Assert-LinkVerseCommand -Name 'docker'

$environmentFile = $context.EnvironmentFile
if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    if (-not $StaticOnly) {
        throw "缺少本地环境文件：$environmentFile"
    }
    $environmentFile = $context.ExampleFile
}

Import-LinkVerseInfrastructureEnvironment -Context $context -EnvironmentFile $environmentFile

Write-Host '正在执行 Docker Compose 静态配置校验……'
Invoke-LinkVerseCompose -Context $context -EnvironmentFile $environmentFile `
    -ComposeArguments @('config', '--quiet')

$digestVariables = @(
    'MYSQL_IMAGE_DIGEST',
    'REDIS_IMAGE_DIGEST',
    'RABBITMQ_IMAGE_DIGEST',
    'NACOS_IMAGE_DIGEST'
)
$missingDigests = @(
    $digestVariables | Where-Object {
        [string]::IsNullOrWhiteSpace((Get-LinkVerseEnvironmentValue -Name $_))
    }
)
$invalidDigests = @(
    $digestVariables | Where-Object {
        $digest = Get-LinkVerseEnvironmentValue -Name $_
        -not [string]::IsNullOrWhiteSpace($digest) -and $digest -notmatch '^sha256:[a-f0-9]{64}$'
    }
)
if ($invalidDigests.Count -gt 0) {
    throw "镜像摘要格式无效，必须为 sha256 加 64 位小写十六进制：$($invalidDigests -join ', ')"
}
if ($missingDigests.Count -gt 0) {
    $digestMessage = "外部镜像仓库核验受阻，摘要尚未锁定：$($missingDigests -join ', ')"
    if ($StaticOnly -or $RequireImageDigests) {
        throw $digestMessage
    }
    Write-Warning $digestMessage
}

if ($StaticOnly) {
    Write-Host '静态基础设施校验通过。'
    return
}

Test-LinkVerseInfrastructureSecrets

Write-Host '正在检查四个中间件容器的运行状态……'
$runningServices = @(
    Invoke-LinkVerseCompose -Context $context -EnvironmentFile $environmentFile `
        -ComposeArguments @('ps', '--services', '--status', 'running')
)
$expectedServices = @('mysql', 'redis', 'rabbitmq', 'nacos')
foreach ($service in $expectedServices) {
    if ($runningServices -notcontains $service) {
        throw "中间件未运行：$service"
    }
}

Write-Host '正在检查业务 Schema 与受限来源的 root 共享账号……'
$databaseOutput = @(
    Invoke-LinkVerseCompose -Context $context -EnvironmentFile $environmentFile `
        -ComposeArguments @(
            'exec', '--no-TTY', 'mysql', 'sh', '-ec',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root --batch --skip-column-names --execute="SHOW DATABASES"'
        )
)
$expectedDatabases = @(
    Get-LinkVerseEnvironmentValue -Name 'IDENTITY_DB_NAME'
    Get-LinkVerseEnvironmentValue -Name 'TRADE_DB_NAME'
    Get-LinkVerseEnvironmentValue -Name 'PAYMENT_DB_NAME'
)
foreach ($database in $expectedDatabases) {
    if ($databaseOutput -notcontains $database) {
        throw "业务 Schema 不存在：$database"
    }
}

$configuredAccounts = @(
    Get-LinkVerseEnvironmentValue -Name 'IDENTITY_APP_USER'
    Get-LinkVerseEnvironmentValue -Name 'IDENTITY_MIGRATOR_USER'
    Get-LinkVerseEnvironmentValue -Name 'TRADE_APP_USER'
    Get-LinkVerseEnvironmentValue -Name 'TRADE_MIGRATOR_USER'
    Get-LinkVerseEnvironmentValue -Name 'PAYMENT_APP_USER'
    Get-LinkVerseEnvironmentValue -Name 'PAYMENT_MIGRATOR_USER'
)
if (@($configuredAccounts | Where-Object { $_ -cne 'root' }).Count -gt 0) {
    throw '本地 MVP 的数据库运行与迁移账号必须统一为 root。'
}

$accountOutput = @(
    Invoke-LinkVerseCompose -Context $context -EnvironmentFile $environmentFile `
        -ComposeArguments @(
            'exec', '--no-TTY', 'mysql', 'sh', '-ec',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root --batch --skip-column-names --execute="SELECT CONCAT(User, CHAR(64), Host) FROM mysql.user"'
        )
)
if ($accountOutput -notcontains 'root@172.18.%') {
    throw '缺少仅供 LinkVerse Docker 网段访问的 root 账号。'
}
if ($accountOutput -contains 'root@%') {
    throw '检测到允许任意来源访问的 root@% 账号。'
}

$grantOutput = @(
    Invoke-LinkVerseCompose -Context $context -EnvironmentFile $environmentFile `
        -ComposeArguments @(
            'exec', '--no-TTY', 'mysql', 'sh', '-ec',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root --batch --skip-column-names --execute="SHOW GRANTS FOR root@''172.18.%''"'
        )
)
$grantText = $grantOutput -join "`n"
foreach ($database in $expectedDatabases) {
    if ($grantText -notmatch [Regex]::Escape($database)) {
        throw "root 共享账号缺少业务 Schema 权限：$database"
    }
}

Write-Host '正在检查 Redis 与 RabbitMQ 隔离资源……'
$redisOutput = @(
    Invoke-LinkVerseCompose -Context $context -EnvironmentFile $environmentFile `
        -ComposeArguments @(
            'exec', '--no-TTY', 'redis', 'sh', '-ec',
            'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ping'
        )
)
if ($redisOutput -notcontains 'PONG') {
    throw 'Redis 鉴权健康检查失败。'
}

$rabbitVhosts = @(
    Invoke-LinkVerseCompose -Context $context -EnvironmentFile $environmentFile `
        -ComposeArguments @('exec', '--no-TTY', 'rabbitmq', 'rabbitmqctl', '--quiet', 'list_vhosts', 'name')
)
$expectedVhost = Get-LinkVerseEnvironmentValue -Name 'RABBITMQ_VHOST'
if ($rabbitVhosts -notcontains $expectedVhost) {
    throw "RabbitMQ vhost 不存在：$expectedVhost"
}

Write-Host '正在检查 Nacos 隔离 namespace 与本地共享账号……'
Test-LinkVerseNacosNamespace
Test-LinkVerseNacosRuntimeLogin

Write-Host '基础设施运行态校验通过。'
