param(
    [Parameter(Mandatory = $true)][string]$BeforeDataset,
    [Parameter(Mandatory = $true)][string]$AfterDataset,
    [Parameter(Mandatory = $true)][string]$ModelRoot,
    [int]$TwoTowerTrials = 30,
    [int]$RankerTrials = 50
)

$ErrorActionPreference = 'Stop'
$PlatformRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$RecommendationRoot = Join-Path $PlatformRoot 'recommendation'
$ExperimentRoot = Join-Path $PlatformRoot 'recommendation-experiments'

Push-Location $RecommendationRoot
try {
    python -m linkverse_recommendation.training.experiment `
        --before-dataset $BeforeDataset `
        --after-dataset $AfterDataset `
        --model-root $ModelRoot `
        --experiment-root $ExperimentRoot `
        --two-tower-trials $TwoTowerTrials `
        --ranker-trials $RankerTrials
} finally {
    Pop-Location
}
