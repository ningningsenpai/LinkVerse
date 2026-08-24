function value(data, metric, field) {
  const values = data.metrics[metric] && data.metrics[metric].values;
  return values && values[field] !== undefined ? values[field] : null;
}

export function buildSummary(data, suite) {
  const measured = {
    suite,
    generated_at: new Date().toISOString(),
    iterations: value(data, 'iterations', 'count'),
    iterations_per_second: value(data, 'iterations', 'rate'),
    checks_rate: value(data, 'checks', 'rate'),
    http_reqs: value(data, 'http_reqs', 'count'),
    http_req_failed_rate: value(data, 'http_req_failed', 'rate'),
    p95_ms: value(data, 'http_req_duration', 'p(95)'),
    p99_ms: value(data, 'http_req_duration', 'p(99)'),
  };
  const markdown = [
    `# ${suite} k6 实测汇总`, '', '| 指标 | 实测值 |', '|---|---:|',
    `| iterations | ${measured.iterations ?? '无'} |`,
    `| throughput (iterations/s) | ${measured.iterations_per_second ?? '无'} |`,
    `| checks rate | ${measured.checks_rate ?? '无'} |`,
    `| HTTP requests | ${measured.http_reqs ?? '无'} |`,
    `| HTTP failure rate | ${measured.http_req_failed_rate ?? '无'} |`,
    `| p95 (ms) | ${measured.p95_ms ?? '无'} |`,
    `| p99 (ms) | ${measured.p99_ms ?? '无'} |`, '',
    '> 本文件只记录本次实测结果，不构成容量承诺。', '',
  ].join('\n');
  const outputs = { stdout: markdown };
  if (__ENV.SUMMARY_JSON) outputs[__ENV.SUMMARY_JSON] = JSON.stringify(measured, null, 2);
  if (__ENV.SUMMARY_MD) outputs[__ENV.SUMMARY_MD] = markdown;
  return outputs;
}
