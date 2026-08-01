// product-detail-hotrow.js — 상품 상세 조회수 UPDATE 락 경합
// 대상: GET /api/products/{id}
// 확인: 읽기 API인데 매 호출마다 같은 row 에 dirty-checking UPDATE(조회수) → 인기 상품에 동시 조회가
//   몰리면 X락으로 직렬화. ⚠️ 락 경합은 p99 에서만 드러남(avg/p95 멀쩡한데 p99 튀면 그게 신호).
//
// 주의:
//   - HOT_IDS / 랜덤 ID 는 삭제·숨김·거래완료가 아닌 "정상 상품 ID"여야 함. 랜덤은 30~40%가 4xx로
//     떨어져 에러율이 오르므로 THRESHOLD_ERR 을 0.05 로 완화해둠. 정상 ID 목록 확보 시 0.01 로 낮출 것.
//   - MAX_PRODUCT_ID / HOT_IDS 를 실제 시드 값으로 교체할 것(시드는 id 1~5 를 정상 유지).
//
// 실행:
//   k6 run -e BASE_URL=http://localhost:8080 product-detail-hotrow.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ── 편집 블록 ──
const TEST_NAME = 'product_detail_hotrow';

// 인기 상품 5개 (실제 시드 데이터의 정상 상품 ID로 교체 필요)
const HOT_IDS = [1, 2, 3, 4, 5];
const MAX_PRODUCT_ID = 100000;
const ENDPOINT = {
  toString() {
    // 50%는 인기 상품 5개에 집중 → 같은 row UPDATE 경합 유발
    if (Math.random() < 0.5) {
      return `/api/products/${HOT_IDS[Math.floor(Math.random() * HOT_IDS.length)]}`;
    }
    return `/api/products/${1 + Math.floor(Math.random() * MAX_PRODUCT_ID)}`;
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
const THRESHOLD_P99_MS = 1000;   // ⚠️ 조회수 락 경합은 p99에서 드러남 — 이 테스트의 핵심 지표
const THRESHOLD_ERR    = 0.05;
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
