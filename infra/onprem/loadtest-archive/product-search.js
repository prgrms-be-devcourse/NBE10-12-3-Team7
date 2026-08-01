// product-search.js — 검색 LIKE 풀스캔
// 대상: GET /api/products/search?keyword=
// 확인: 선행 와일드카드 LIKE '%kw%' + LOWER() 로 인덱스를 못 타 전체 row 스캔 → 데이터 늘수록 느려짐.
//   매 요청 다른 키워드로 캐시 히트를 막는다. 공개 API라 토큰 불필요.
//
// 실행:
//   k6 run -e BASE_URL=http://localhost:8080 product-search.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ── 편집 블록 ──
const TEST_NAME = 'product_search';

// 매 요청 다른 키워드 (캐시 히트 방지). ENDPOINT.toString() 이 URL 조립 시 호출되어 매번 랜덤화됨.
const KEYWORDS = ['의자', '책상', '노트북', '자전거', '카메라', '냉장고',
                  '소파', '운동화', '가방', '모니터', '침대', '에어컨'];
const ENDPOINT = {
  toString() {
    const kw = KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)];
    return `/api/products/search?keyword=${encodeURIComponent(kw)}`;
  },
};

const METHOD = 'GET';
function buildBody() {
  return null;
}

const STAGES = [
  { duration: '30s', target: 10 },
  { duration: '1m',  target: 30 },
  { duration: '1m',  target: 50 },
  { duration: '30s', target: 0 },
];

// 기준 1000ms는 일부러 느슨하게. 통과하면 낮춰가며 실제 한계를 찾는다.
const THRESHOLD_P95_MS = 1000;
const THRESHOLD_P99_MS = 2000;
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
