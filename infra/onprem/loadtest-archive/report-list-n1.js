// report-list-n1.js — 내 신고 내역 조회 N+1 방지(EntityGraph) 검증
// 대상: GET /api/members/me/reports
// 확인: findAllByReporter() 의 @EntityGraph 로 N+1 을 없앤 부분이 부하에도 응답시간이 안정적인가.
//   ⚠️ 전제: 테스트 계정에 신고가 최소 수십 건 있어야 의미 있음(시드는 신고자 member2 에 40건).
//
// 실행:
//   k6 run -e BASE_URL=http://localhost:8080 -e TOKEN=<신고내역보유JWT> report-list-n1.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ── 편집 블록 ──
const TEST_NAME = 'report_list_n1';
const ENDPOINT = '/api/members/me/reports';
const METHOD = 'GET';

function buildBody() {
  return null;
}

const STAGES = [
  { duration: '15s', target: 10 },
  { duration: '30s', target: 50 },
  { duration: '15s', target: 0 },
];

const THRESHOLD_P95_MS = 300;
const THRESHOLD_P99_MS = 600;
const THRESHOLD_ERR    = 0.01;
const REQ_TIMEOUT = '10s';

// ── 공통(수정 금지) ──
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN    = __ENV.TOKEN    || '';

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
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${TOKEN}`,
    },
    timeout: REQ_TIMEOUT,
    tags: { name: TEST_NAME },   // name 라벨 = API 식별자
  };

  const res = METHOD === 'GET'
    ? http.get(url, params)
    : http.post(url, body, params);

  latency.add(res.timings.duration);
  errors.add(res.status !== 200);

  check(res, {
    'status 200': (r) => r.status === 200,
    'body 비어있지 않음': (r) => r.body && r.body.length > 0,
  });

  sleep(1);
}
