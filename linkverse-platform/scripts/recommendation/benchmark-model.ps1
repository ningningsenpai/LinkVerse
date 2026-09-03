param(
    [Parameter(Mandatory = $true)][string]$Bundle,
    [Parameter(Mandatory = $true)][string]$Output,
    [int]$Concurrency = 20,
    [int]$Calls = 400,
    [int]$CandidateCount = 300
)

$ErrorActionPreference = 'Stop'
$RecommendationRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..\recommendation')

Push-Location $RecommendationRoot
try {
    python -m linkverse_recommendation.training.serving_benchmark `
        --bundle $Bundle `
        --output $Output `
        --concurrency $Concurrency `
        --calls $Calls `
        --candidate-count $CandidateCount
    if ($LASTEXITCODE -ne 0) {
        throw "Recommendation 模型并发基准失败，退出码：$LASTEXITCODE"
    }
} finally {
    Pop-Location
}
