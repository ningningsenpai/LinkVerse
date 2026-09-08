param([Parameter(Mandatory = $true)][string]$ModelRoot, [switch]$Build)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\_delivery.ps1')
$context = Initialize-LinkVerseDeliveryEnvironment
$env:RECOMMENDATION_MODEL_ROOT = (Resolve-Path -LiteralPath $ModelRoot).Path.Replace('\', '/')
if (-not (Test-Path -LiteralPath (Join-Path $ModelRoot 'active-model.json'))) { throw '模型根目录没有活动指针' }
if ($Build) {
    Invoke-LinkVerseCompose -Context $context.Infrastructure -EnvironmentFile $context.Infrastructure.EnvironmentFile -ComposeArguments @('build','recommendation')
}
Invoke-LinkVerseCompose -Context $context.Infrastructure -EnvironmentFile $context.Infrastructure.EnvironmentFile -ComposeArguments @('--profile','recommendation','up','--detach','--wait','--wait-timeout','90','recommendation')
$deadline = [DateTimeOffset]::UtcNow.AddSeconds(60)
do {
    try {
        $ready = Invoke-RestMethod 'http://127.0.0.1:18084/health/ready' -TimeoutSec 2
        if ($ready.status -eq 'UP') { break }
    } catch { }
    Start-Sleep -Milliseconds 500
} while ([DateTimeOffset]::UtcNow -lt $deadline)
if ($null -eq $ready -or $ready.status -ne 'UP') { throw '推荐容器模型未就绪' }
@{model_root=$env:RECOMMENDATION_MODEL_ROOT; model_version=$ready.model_version; port=18084} | ConvertTo-Json | Set-Content (Join-Path $context.RuntimeRoot 'recommendation-serving.json') -Encoding utf8
Write-Host "推荐 CPU 容器已就绪：$($ready.model_version)"
