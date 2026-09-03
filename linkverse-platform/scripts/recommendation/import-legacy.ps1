param(
    [Parameter(Mandatory = $true)][string]$Items,
    [Parameter(Mandatory = $true)][string]$Users,
    [Parameter(Mandatory = $true)][string]$Interactions,
    [Parameter(Mandatory = $true)][string]$Output
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($env:RECOMMENDATION_USER_HMAC_SECRET)) {
    throw '未配置 RECOMMENDATION_USER_HMAC_SECRET，不能生成稳定匿名用户键'
}

Push-Location (Join-Path $PSScriptRoot '..\..\recommendation')
try {
    python -m linkverse_recommendation.data.legacy `
        --items $Items `
        --users $Users `
        --interactions $Interactions `
        --output $Output
} finally {
    Pop-Location
}
