[CmdletBinding()]
param(
    [ValidateRange(30, 900)]
    [int]$WaitTimeoutSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '_infrastructure.ps1')

$context = Get-LinkVerseInfrastructureContext
Assert-LinkVerseCommand -Name 'docker'

if (-not (Test-Path -LiteralPath $context.EnvironmentFile -PathType Leaf)) {
    throw "缺少本地环境文件。请先复制并填写：Copy-Item '$($context.ExampleFile)' '$($context.EnvironmentFile)'"
}

Import-LinkVerseInfrastructureEnvironment -Context $context -EnvironmentFile $context.EnvironmentFile
Test-LinkVerseInfrastructureSecrets

Write-Host '正在校验 Docker Compose 配置……'
Invoke-LinkVerseCompose -Context $context -EnvironmentFile $context.EnvironmentFile `
    -ComposeArguments @('config', '--quiet')

Write-Host '正在启动 LinkVerse MVP 中间件……'
Invoke-LinkVerseCompose -Context $context -EnvironmentFile $context.EnvironmentFile `
    -ComposeArguments @(
        'up', '--detach', '--wait', '--wait-timeout', [string]$WaitTimeoutSeconds,
        'mysql', 'redis', 'rabbitmq', 'nacos'
    )

Write-Host '正在收敛三个 Schema 与六个数据库账号的授权……'
Invoke-LinkVerseCompose -Context $context -EnvironmentFile $context.EnvironmentFile `
    -ComposeArguments @(
        'exec', '--no-TTY', 'mysql',
        'bash', '/docker-entrypoint-initdb.d/10-create-business-databases.sh'
    )

Write-Host '正在收敛 RabbitMQ vhost、账号与权限……'
$rabbitBootstrapCommand = @'
rabbitmqctl --quiet list_vhosts name | grep -Fxq "$RABBITMQ_DEFAULT_VHOST" || rabbitmqctl add_vhost "$RABBITMQ_DEFAULT_VHOST"
if rabbitmqctl --quiet list_users | awk '{print $1}' | grep -Fxq "$RABBITMQ_DEFAULT_USER"; then
    rabbitmqctl change_password "$RABBITMQ_DEFAULT_USER" "$RABBITMQ_DEFAULT_PASS"
else
    rabbitmqctl add_user "$RABBITMQ_DEFAULT_USER" "$RABBITMQ_DEFAULT_PASS"
fi
if [ "$RABBITMQ_DEFAULT_USER" != 'guest' ] && rabbitmqctl --quiet list_users | awk '{print $1}' | grep -Fxq 'guest'; then
    rabbitmqctl delete_user guest
fi
rabbitmqctl set_user_tags "$RABBITMQ_DEFAULT_USER" management
rabbitmqctl set_permissions --vhost "$RABBITMQ_DEFAULT_VHOST" "$RABBITMQ_DEFAULT_USER" '.*' '.*' '.*'
'@
$rabbitBootstrapCommand = $rabbitBootstrapCommand.Replace("`r`n", "`n")
Invoke-LinkVerseCompose -Context $context -EnvironmentFile $context.EnvironmentFile `
    -ComposeArguments @('exec', '--no-TTY', 'rabbitmq', 'sh', '-ec', $rabbitBootstrapCommand)

Write-Host '正在初始化 Nacos 管理员、隔离 namespace 与 runtime 权限……'
Initialize-LinkVerseNacos | Out-Null

$nacosConsolePort = Get-LinkVerseEnvironmentValue -Name 'NACOS_CONSOLE_HOST_PORT'
$rabbitManagementPort = Get-LinkVerseEnvironmentValue -Name 'RABBITMQ_MANAGEMENT_HOST_PORT'
Write-Host '本地基础设施已完成幂等初始化。'
Write-Host "Nacos 控制台：http://127.0.0.1:$nacosConsolePort/"
Write-Host "RabbitMQ 管理端：http://127.0.0.1:$rabbitManagementPort/"
