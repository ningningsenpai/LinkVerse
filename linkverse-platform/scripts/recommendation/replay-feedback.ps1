param(
    [Parameter(Mandatory = $true)][string]$Cases,
    [Parameter(Mandatory = $true)][string]$Output,
    [string]$BaseUrl = 'http://127.0.0.1:18080'
)

$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..\..\recommendation')
try {
    python -m linkverse_recommendation.training.feedback_replay `
        --base-url $BaseUrl --cases $Cases --output $Output
    if ($LASTEXITCODE -ne 0) {
        throw "Trade 推荐 HTTP 反馈回放失败，退出码：$LASTEXITCODE"
    }
} finally {
    Pop-Location
}
