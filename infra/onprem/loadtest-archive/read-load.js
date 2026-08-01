// 읽기 부하 시나리오: 알림 배지(unread-count) + 알림 목록 + 댓글 목록 + 내 찜 목록.
// unread-count 는 내부적으로 채팅방 3쿼리(getMyRooms)를 재사용하는 "고빈도 × 숨은 조인" 엔드포인트라 가장 무겁게 가중.
//
// 실행(이 폴더에서):
//   k6 run -e BASE_URL=http://localhost -e EMAIL=test@a.com -e PASSWORD=pw1234! -e PRODUCT_ID=1 read-load.js
//
// 환경변수:
//   BASE_URL    기본 http://localhost  (LAN 부하 시 http://<서버IP>)
//   EMAIL/PASSWORD  로그인할 시드 계정 (미리 회원가입 되어 있어야 함)
//   PRODUCT_ID  댓글 조회에 쓸, 접근 가능한 상품 id

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EMAIL = __ENV.EMAIL || 'brian3092@naver.com';
const PASSWORD = __ENV.PASSWORD || 'test1234!@#';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';

const errors = new Rate('business_errors');

export const options = {
  scenarios: {
    read_mix: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 20 },   // 워밍업
        { duration: '1m', target: 50 },     // 상승
        { duration: '2m', target: 50 },     // 유지(측정 구간)
        { duration: '30s', target: 0 },     // 정리
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],            // 실패율 1% 미만
    business_errors: ['rate<0.01'],
    // 엔드포인트별 p95·p99 동시 노출 (기준값은 baseline 측정 후 조정). 태그 서브메트릭은
    // threshold 를 걸어야 요약에 개별로 표시되므로 4개 엔드포인트 모두 명시.
    'http_req_duration{name:unread_count}':  ['p(95)<300', 'p(99)<600'],
    'http_req_duration{name:notifications}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{name:comments}':      ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{name:my_favorites}':  ['p(95)<500', 'p(99)<1000'],
  },
  // 요약에 p90/p95/p99 를 항상 동일 포맷으로 출력 (개선 전후 비교 기준선)
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 로그인은 부하 대상이 아니므로 테스트 시작 전 setup()에서 한 번만 수행하고 토큰을 공유한다.
export function setup() {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email: EMAIL, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { '로그인 200': (r) => r.status === 200 });
  const token = res.json('data.accessToken');
  if (!token) {
    throw new Error(`로그인 실패: status=${res.status} body=${res.body}`);
  }
  return { token };
}

export default function (data) {
  const authParams = {
    headers: { Authorization: `Bearer ${data.token}` },
  };

  // 1) 알림 배지 — 가장 자주 호출되는 핫스팟(헤더 폴링). name 태그로 별도 집계.
  const unread = http.get(`${BASE_URL}/api/notifications/unread-count`, {
    ...authParams,
    tags: { name: 'unread_count' },
  });
  check(unread, { 'unread-count 200': (r) => r.status === 200 }) || errors.add(1);

  // 2) 알림 목록
  const notis = http.get(`${BASE_URL}/api/notifications`, {
    ...authParams,
    tags: { name: 'notifications' },
  });
  check(notis, { 'notifications 200': (r) => r.status === 200 }) || errors.add(1);

  // 3) 댓글 목록 — 비로그인 공개 조회(토큰 불필요). 인기글 읽기 처리량.
  const comments = http.get(`${BASE_URL}/api/products/${PRODUCT_ID}/comments`, {
    tags: { name: 'comments' },
  });
  check(comments, { 'comments 200': (r) => r.status === 200 }) || errors.add(1);

  // 4) 내 찜 목록
  const favs = http.get(`${BASE_URL}/api/members/me/favorites`, {
    ...authParams,
    tags: { name: 'my_favorites' },
  });
  check(favs, { 'my-favorites 200': (r) => r.status === 200 }) || errors.add(1);

  sleep(1); // 유저 think-time
}