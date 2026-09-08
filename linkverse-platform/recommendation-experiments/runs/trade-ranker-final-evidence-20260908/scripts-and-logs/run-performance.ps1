$ErrorActionPreference = 'Stop'
$root = 'D:\Code\ning\LinkVerse'
Set-Location $root
. ./linkverse-platform/scripts/_delivery.ps1
$context = Initialize-LinkVerseDeliveryEnvironment
$roundCache = Join-Path $root '.cache/recommendation-ranker-20260908'
$runs = Join-Path $root 'linkverse-platform/recommendation-experiments/runs'
$formal = Get-Content (Join-Path $roundCache 'formal-config.json') | ConvertFrom-Json
$selection = Get-Content (Join-Path $runs 'trade-ranker-calibration-20260908/selection.json') | ConvertFrom-Json
$runtime = Get-Content (Join-Path $runs 'trade-ranker-runtime-v2-20260908/summary.json') | ConvertFrom-Json
$evaluation = Get-Content (Join-Path $runs 'trade-ranker-request-time-20260908/status.json') | ConvertFrom-Json
if (-not $runtime.passed -or $evaluation.status -ne 'COMPLETED') { throw '功能验证或离线复评尚未完成，拒绝混合性能测量' }
$recEnv = (Resolve-Path 'linkverse-platform/recommendation/.venv-conda').Path
$env:PATH="$recEnv;$recEnv\Library\bin;$recEnv\Scripts;$env:PATH"
$env:PYTHONIOENCODING='utf-8'
$env:PYTHONDONTWRITEBYTECODE='1'
$env:OMP_NUM_THREADS='1'
$env:OPENBLAS_NUM_THREADS='1'
$env:MKL_NUM_THREADS='1'
$python = Join-Path $recEnv 'python.exe'
$objects = @(Get-Content (Join-Path $formal.base_bundle 'object-ids.json') | ConvertFrom-Json)
$identityProcess = Get-Content (Join-Path $roundCache 'identity-process.json') | ConvertFrom-Json
foreach ($name in @('before','after')) {
    $bundle = if ($name -eq 'before') { Join-Path $formal.model_root (Split-Path $formal.base_bundle -Leaf) } else { $selection.bundle }
    & $python -m linkverse_recommendation.serving.activate --model-root $formal.model_root --bundle $bundle --purpose ENGINEERING
    if ($LASTEXITCODE -ne 0) { throw '性能测试隔离包激活失败' }
    $version = (Get-Content (Join-Path $bundle 'manifest.json') | ConvertFrom-Json).model_version
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(30)
    $ready = $null
    do {
        try { $ready = Invoke-RestMethod 'http://127.0.0.1:18085/health/ready' -TimeoutSec 5 } catch { $ready = $null }
        if ($null -ne $ready -and $ready.model_version -eq $version) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if ($null -eq $ready -or $ready.model_version -ne $version) { throw '隔离测试模型未就绪' }
    $stages = @()
    foreach ($count in @(100,300)) {
        $stages += @{name="$name-$count-vu20";target='python';port=18085;candidate_count=$count;concurrency=20;
            warmup_seconds=15;duration_seconds=60;think_seconds=0;stop_on_failure=$false;context_ids=@($objects | Select-Object -First 20)}
    }
    if ($name -eq 'after') {
        $stages += @{name='after-sustained-vu20';target='python';port=18085;candidate_count=300;concurrency=20;
            warmup_seconds=15;duration_seconds=120;think_seconds=1;stop_on_failure=$false;context_ids=@($objects | Select-Object -First 20)}
    }
    $settings = @{output=(Join-Path $runs "trade-ranker-http-$name-20260908");bundle=$bundle;
        fixture=(Join-Path $root 'linkverse-platform/.runtime/recommendation-fixture.json');python_known_user_count=10;
        container_metrics=$true;metric_containers=@('linkverse-mvp-recommendation-ranker-20260908');java_processes=@($identityProcess);
        container_cpu_limit=4;container_memory_gib=6;stages=$stages;purpose='SAME_CONTAINER_PAIRED_DIRECT_HTTP'}
    $configPath = Join-Path $roundCache "http-$name-config.json"
    $settings | ConvertTo-Json -Depth 9 | Set-Content $configPath -Encoding utf8
    & $python -m linkverse_recommendation.training.http_benchmark --config $configPath *> (Join-Path $roundCache "http-$name.log")
    if ($LASTEXITCODE -ne 0) { throw 'HTTP 性能执行失败，保留原始输出' }
    Write-Output "HTTP 性能采样完成：$name"
}
$settings = @{output=(Join-Path $runs 'trade-ranker-inprocess-20260908');bundle=$selection.bundle;purpose='FINAL_CANDIDATE_CPU_REPEATS';resource_monitor=$true}
$settings | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $roundCache 'inprocess-config.json') -Encoding utf8
& $python -m linkverse_recommendation.training.inprocess_validation --config (Join-Path $roundCache 'inprocess-config.json') *> (Join-Path $roundCache 'inprocess.log')
if ($LASTEXITCODE -ne 0) { throw '进程内性能复测执行失败' }
Write-Output '本轮全部性能测试执行完成'
