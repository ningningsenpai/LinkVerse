param(
    [string]$Platform = 'win-64'
)

$ErrorActionPreference = 'Stop'
$RecommendationRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..\recommendation')
$CondaLock = Join-Path $RecommendationRoot '.venv-conda\Scripts\conda-lock.exe'
$GeneratedLock = Join-Path $RecommendationRoot "conda-lock.$([Guid]::NewGuid().ToString('N')).yml"
$FinalLock = Join-Path $RecommendationRoot 'conda-lock.yml'
$PreviousPoetryTimeout = $env:POETRY_REQUESTS_TIMEOUT

if (-not (Test-Path -LiteralPath $CondaLock -PathType Leaf)) {
    throw '推荐环境中缺少 conda-lock，请先按 environment.yml 创建或更新环境'
}

Push-Location $RecommendationRoot
try {
    # CUDA wheel 接近 2 GiB，默认 15 秒读超时不足以完成元数据解析。
    $env:POETRY_REQUESTS_TIMEOUT = '300'
    & $CondaLock lock --file environment.yml --platform $Platform --lockfile $GeneratedLock
    if ($LASTEXITCODE -ne 0) {
        throw "conda-lock 生成失败，退出码：$LASTEXITCODE"
    }
    Move-Item -LiteralPath $GeneratedLock -Destination $FinalLock -Force
} finally {
    Pop-Location
    $env:POETRY_REQUESTS_TIMEOUT = $PreviousPoetryTimeout
    if (Test-Path -LiteralPath $GeneratedLock -PathType Leaf) {
        Remove-Item -LiteralPath $GeneratedLock -Force
    }
}
