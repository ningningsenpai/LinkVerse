param(
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$JavaHome = 'D:\CodeTools\JavaJDK\microsoft-jdk-21.0.12\jdk-21.0.12+8'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\_delivery.ps1')

$context = Initialize-LinkVerseDeliveryEnvironment -JavaHome $JavaHome
if ([string]::IsNullOrWhiteSpace($env:RECOMMENDATION_USER_HMAC_SECRET)) {
    throw '未配置 RECOMMENDATION_USER_HMAC_SECRET'
}
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
[Environment]::SetEnvironmentVariable(
    'RECOMMENDATION_SNAPSHOT_OUTPUT',
    [IO.Path]::GetFullPath((Resolve-Path $OutputRoot).Path),
    'Process'
)

Push-Location $context.BackendRoot
try {
    & (Join-Path $context.BackendRoot 'mvnw.cmd') `
        '-pl' 'linkverse-trade' '-am' 'spring-boot:run' `
        '-Dspring-boot.run.profiles=snapshot-export'
    if ($LASTEXITCODE -ne 0) {
        throw "Java 只读快照导出失败，退出码：$LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$latestPointer = Join-Path $OutputRoot 'latest-export.txt'
if (-not (Test-Path -LiteralPath $latestPointer -PathType Leaf)) {
    throw '快照导出未生成 latest-export.txt'
}
$snapshot = (Get-Content -LiteralPath $latestPointer -Raw).Trim()
Push-Location (Join-Path $context.PlatformRoot 'recommendation')
try {
    python -m linkverse_recommendation.data.snapshot --snapshot $snapshot
    if ($LASTEXITCODE -ne 0) {
        throw "Parquet 转换失败，退出码：$LASTEXITCODE"
    }
} finally {
    Pop-Location
}
