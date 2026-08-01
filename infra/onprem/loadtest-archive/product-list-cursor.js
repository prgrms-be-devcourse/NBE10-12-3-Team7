// product-list-cursor.js — 커서 깊은 페이지
// 대상: GET /api/products?cursor=&size=30
// 확인: 필터(deleted/hidden/status/trade) 통과율이 낮으면 31건 채우려 수천 row 스캔 → 커서 깊을수록 악화.
//   커서 조건이 id < cursor 라 값이 작을수록 뒤쪽 페이지. 공개 API라 토큰 불필요.
//   ⚠️ MAX_PRODUCT_ID 를 실제 시드 건수(seed 요약행의 max_product_id)로 교체할 것.
//
// 실행:
//   k6 run -e BASE_URL=http://localhost:8080 product-list-cursor.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ── 편집 블록 ──
const TEST_NAME = 'product_list_cursor';

// 시드 데이터 최대 상품 ID (실제 넣은 건수에 맞게 조정)
const MAX_PRODUCT_ID = 100000;
const ENDPOINT = {
  toString() {
    // 커서를 전 구간에 뿌려 깊은 페이지 포함
    const cursor = 1 + Math.floor(Math.random() * MAX_PRODUCT_ID);
    return `/api/products?cursor=${cursor}&size=30`;
  },
};

const METHOD = 'GET';
function buildBody() {
  return null;
}

const STAGES = [
  { duration: '30s', target: 10 },
  { duration: '1m',  target: 50 },
  { duration: '1m',  target: 100 },
  { duration: '30s', target: 0 },
];

const THRESHOLD_P95_MS = 500;
const THRESHOLD_P99_MS = 1000;
const THRESHOLD_ERR    = 0.01;
const REQ_TIMEOUT = '30s';

// ── 공통(수정 금지) ──
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const latency = new Trend(`${TEST_NAME}_latency`, true);
const errors  = new Rate(`${TEST_NAME}_errors`);

export const options = {
  scenarios: {
    [TEST_NAME]: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: STAGES,
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    [`${TEST_NAME}_errors`]:  [`rate<${THRESHOLD_ERR}`],
    [`${TEST_NAME}_latency`]: [`p(95)<${THRESHOLD_P95_MS}`, `p(99)<${THRESHOLD_P99_MS}`],
    http_req_failed:          [`rate<${THRESHOLD_ERR}`],
  },
  // 요약에 p90/p95/p99 를 항상 동일 포맷으로 출력 (개선 전후 비교 기준선)
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const url = `${BASE_URL}${ENDPOINT}`;
  const body = buildBody();
  const params = { timeout: REQ_TIMEOUT, tags: { name: TEST_NAME } };  // name 라벨 = API 식별자(URL 랜덤화 무시)

  const res = METHOD === 'GET'
    ? http.get(url, params)
    : http.post(url, body, params);

  latency.add(res.timings.duration);
  errors.add(res.status !== 200);

  check(res, {
    'status 200': (r) => r.status === 200,
  });

  sleep(1);
}
