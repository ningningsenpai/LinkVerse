import http from 'k6/http';
import { check } from 'k6';
import { buildSummary } from './summary.js';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const token = __ENV.ACCESS_TOKEN || '';

export const options = {
  scenarios: {
    payment_chain: {
      executor: 'shared-iterations', vus: Number(__ENV.VUS || 1),
      iterations: Number(__ENV.ITERATIONS || 1), maxDuration: '3m',
    },
  },
};

export function setup() {
  if (!token) throw new Error('必须通过 ACCESS_TOKEN 提供本地测试令牌');
}

export default function () {
  const suffix = `${__VU}-${__ITER}-${Date.now()}`;
  const order = http.post(`${baseUrl}/api/v1/orders`, JSON.stringify({ listing_id: 10001, quantity: 1 }), {
    headers: {
      Authorization: `Bearer ${token}`, 'Content-Type': 'application/json',
      'Idempotency-Key': `k6-pay-order-${suffix}`,
    },
    tags: { operation: 'payment_order_create' },
  });
  check(order, { '支付测试订单创建成功': (response) => response.status === 201 });
  if (order.status !== 201) return;
  const orderNo = order.json('order_no');
  const intent = http.put(`${baseUrl}/api/v1/orders/${orderNo}/payment-intent`, null, {
    headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': `k6-pay-${suffix}` },
    tags: { operation: 'payment_intent_create' },
  });
  check(intent, { '支付 Intent 创建成功': (response) => response.status === 200 });
  if (intent.status !== 200) return;
  const confirmed = http.post(
    `${baseUrl}/api/v1/mock-provider/payment-intents/${intent.json('intent_no')}/confirm`, null,
    {
      headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': `k6-confirm-${suffix}` },
      tags: { operation: 'mock_payment_confirm' },
    },
  );
  check(confirmed, {
    'Mock 支付确认成功': (response) => response.status === 200,
    '支付状态已成功': (response) => response.json('status') === 'SUCCEEDED',
  });
}

export function handleSummary(data) {
  return buildSummary(data, '支付链路');
}
