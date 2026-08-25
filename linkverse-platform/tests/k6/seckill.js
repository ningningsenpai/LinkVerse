import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { buildSummary } from './summary.js';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const tokens = new SharedArray('tokens', () => JSON.parse(open(__ENV.TOKENS_FILE || './tokens.example.json')));
const users = Number(__ENV.USERS || 1000);
const vus = Number(__ENV.SECKILL_VUS || 100);
const campaignId = Number(__ENV.CAMPAIGN_ID || 20001);
const accepted = new Counter('seckill_accepted');
const rejected = new Counter('seckill_rejected');
const unexpected = new Counter('seckill_unexpected');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    seckill: { executor: 'shared-iterations', vus, iterations: users, maxDuration: '5m' },
  },
};

export function setup() {
  if (tokens.length < users) {
    throw new Error(`秒杀测试需要 ${users} 个独立用户令牌，实际只有 ${tokens.length} 个`);
  }
}

export default function () {
  const userIndex = exec.scenario.iterationInTest;
  const response = http.post(`${baseUrl}/api/v1/seckill/campaigns/${campaignId}/reservations`, null, {
    headers: {
      Authorization: `Bearer ${tokens[userIndex]}`, 'Idempotency-Key': `k6-seckill-user-${userIndex + 1}`,
    },
    tags: { operation: 'seckill_reserve' },
    responseCallback: http.expectedStatuses(202, 409),
  });
  if (response.status === 202) accepted.add(1);
  else if (response.status === 409) rejected.add(1);
  else unexpected.add(1);
  check(response, { '秒杀请求得到受控响应': (result) => [202, 409].includes(result.status) });
}

export function handleSummary(data) {
  return buildSummary(data, '商品秒杀');
}
