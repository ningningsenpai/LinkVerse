import http from 'k6/http';
import { check } from 'k6';
import { buildSummary } from './summary.js';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const token = __ENV.ACCESS_TOKEN || '';

export const options = {
  scenarios: {
    normal_trade: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 1),
      iterations: Number(__ENV.ITERATIONS || 1),
      maxDuration: '2m',
    },
  },
};

export function setup() {
  if (!token) throw new Error('必须通过 ACCESS_TOKEN 提供本地测试令牌');
}

export default function () {
  const key = `k6-normal-${__VU}-${__ITER}-${Date.now()}`;
  const headers = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
    'Idempotency-Key': key,
  };
  const created = http.post(`${baseUrl}/api/v1/orders`, JSON.stringify({ listing_id: 10001, quantity: 1 }), {
    headers, tags: { operation: 'order_create' },
  });
  check(created, { '普通订单创建成功': (response) => response.status === 201 });
  if (created.status !== 201) return;
  const orderNo = created.json('order_no');
  const queried = http.get(`${baseUrl}/api/v1/orders/${orderNo}`, {
    headers: { Authorization: `Bearer ${token}` }, tags: { operation: 'order_query' },
  });
  check(queried, {
    '订单所有者查询成功': (response) => response.status === 200,
    '订单号保持一致': (response) => response.json('order_no') === orderNo,
  });
}

export function handleSummary(data) {
  return buildSummary(data, '普通交易');
}
