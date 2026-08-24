[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [switch]$RemoveData,
    [string]$ConfirmProject
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_delivery.ps1')

$context = Initialize-LinkVerseDeliveryEnvironment
$pidFile = Join-Path $context.RuntimeRoot 'services.json'
if (Test-Path -LiteralPath $pidFile) {
    foreach ($service in @(Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($service.pid)" -ErrorAction SilentlyContinue
        if ($null -ne $process) {
            $expectedJar = [IO.Path]::GetFullPath([string]$service.jar)
            if (-not ([string]$process.CommandLine).Contains($expectedJar, [StringComparison]::OrdinalIgnoreCase)) {
                throw "PID $($service.pid) 不再属于记录的 LinkVerse 服务，已拒绝终止。"
            }
            Stop-Process -Id $service.pid -Force
        }
    }
    Remove-Item -LiteralPath $pidFile -Force
}

$composeArguments = @('down', '--remove-orphans')
if ($RemoveData) {
    $expectedProject = Get-LinkVerseEnvironmentValue -Name 'COMPOSE_PROJECT_NAME'
    if ($ConfirmProject -cne $expectedProject -or $ConfirmProject -cne 'linkverse-mvp') {
        throw '删除本地数据前必须同时传入 -RemoveData -ConfirmProject linkverse-mvp。'
    }
    if ($PSCmdlet.ShouldProcess($expectedProject, '删除 LinkVerse Compose 容器和命名卷')) {
        $composeArguments += '--volumes'
    }
    else {
        return
    }
}
Invoke-LinkVerseCompose -Context $context.Infrastructure `
    -EnvironmentFile $context.Infrastructure.EnvironmentFile -ComposeArguments $composeArguments
Write-Host ($RemoveData ? '本地服务、中间件与 LinkVerse 命名卷已清理。' : '本地服务和中间件已停止，数据卷已保留。')
