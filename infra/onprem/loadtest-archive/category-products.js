// category-products.js — 페이지네이션 없는 목록
// 대상: GET /api/categories/{id}/products
// 확인: 커서 페이지네이션이 없어 조건에 맞는 전체 row 를 메모리에 올려 DTO 변환 → 데이터 늘수록 힙 압박.
//   ⚠️ k6 숫자보다 서버 힙 사용량과 GC 로그를 봐야 함. 공개 API라 토큰 불필요.
//
// 실행:
//   k6 run -e BASE_URL=http://localhost:8080 category-products.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ── 편집 블록 ──
const TEST_NAME = 'category_products_no_pagination';

// 카테고리 8개 (디지털기기 ~ 기타)
const ENDPOINT = {
  toString() {
    const categoryId = 1 + Math.floor(Math.random() * 8);
    return `/api/categories/${categoryId}/products`;
  },
};

const METHOD = 'GET';
function buildBody() {
  return null;
}

const STAGES = [
  { duration: '30s', target: 5 },
  { duration: '1m',  target: 20 },
  { duration: '1m',  target: 50 },
  { duration: '30s', target: 0 },
];

const THRESHOLD_P95_MS = 2000;
const THRESHOLD_P99_MS = 4000;
const THRESHOLD_ERR    = 0.01;
const REQ_TIMEOUT = '60s';

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
