import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { buildSummary } from './summary.js';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const tokens = new SharedArray('tokens', () => JSON.parse(open(__ENV.TOKENS_FILE || './tokens.example.json')));
const users = Number(__ENV.USERS || 1000);

export const options = {
  scenarios: {
    seckill: { executor: 'per-vu-iterations', vus: users, iterations: 1, maxDuration: '5m' },
  },
};

export function setup() {
  if (tokens.length < users) {
    throw new Error(`秒杀测试需要 ${users} 个独立用户令牌，实际只有 ${tokens.length} 个`);
  }
}

export default function () {
  const response = http.post(`${baseUrl}/api/v1/seckill/campaigns/20001/reservations`, null, {
    headers: {
      Authorization: `Bearer ${tokens[__VU - 1]}`, 'Idempotency-Key': `k6-seckill-user-${__VU}`,
    },
    tags: { operation: 'seckill_reserve' },
  });
  check(response, { '秒杀请求得到受控响应': (result) => [202, 409].includes(result.status) });
}

export function handleSummary(data) {
  return buildSummary(data, '商品秒杀');
}
