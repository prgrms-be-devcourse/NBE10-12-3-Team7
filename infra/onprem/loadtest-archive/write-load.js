// 쓰기 부하 시나리오: 댓글 작성(POST). 매 요청이 새 row 를 만들고,
// 남의 상품이면 AFTER_COMMIT 으로 알림 이벤트까지 발행되는 쓰기 경로를 측정한다.
// (찜 add/remove 는 (member,product) UNIQUE 라 단일 계정 동시 반복 시 충돌 에러가 섞이므로,
//  깨끗한 쓰기 처리량 측정에는 유니크 제약이 없는 댓글 작성이 적합하다.)
//
// 주의: 실행할수록 comment row 가 계속 쌓인다. 테스트 후 정리 쿼리로 삭제할 것.
//
// 실행(이 폴더에서):
//   k6 run -e BASE_URL=http://localhost -e EMAIL=test@a.com -e PASSWORD=pw1234! -e PRODUCT_ID=1 write-load.js

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';
const EMAIL = __ENV.EMAIL || 'test@a.com';
const PASSWORD = __ENV.PASSWORD || 'pw1234!';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';

export const options = {
  scenarios: {
    write_comments: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '1m', target: 30 },
        { duration: '1m', target: 30 },
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:create_comment}': ['p(95)<600', 'p(99)<1200'],
  },
  // 요약에 p90/p95/p99 를 항상 동일 포맷으로 출력 (개선 전후 비교 기준선)
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email: EMAIL, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' },
  });
  const token = res.json('data.accessToken');
  if (!token) {
    throw new Error(`로그인 실패: status=${res.status} body=${res.body}`);
  }
  return { token };
}

export default function (data) {
  const body = JSON.stringify({ content: `부하테스트 댓글 vu=${__VU} iter=${__ITER} ts=${Date.now()}` });
  const res = http.post(`${BASE_URL}/api/products/${PRODUCT_ID}/comments`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
    tags: { name: 'create_comment' },
  });
  check(res, { 'create-comment 201': (r) => r.status === 201 });
  sleep(1);
}
