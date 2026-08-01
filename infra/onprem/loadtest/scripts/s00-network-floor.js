// 시나리오 00 — 바닥값 측정. **다른 시나리오보다 먼저 돌린다.**
//
// 왜 필요한가: 부하를 노트북에서 LAN 으로 걸면 응답시간에 네트워크 구간이 섞인다.
// 이 바닥값을 모르면 "VU 를 올렸더니 느려졌다"가 앱 때문인지 네트워크 때문인지 갈라낼 수 없다.
//
// 무엇을 재는가: 가장 가벼운 경로(`/api/categories` — 8건 고정, 0.2 KB)에 같은 VU 계단을 건다.
// 순수한 네트워크만은 아니고 **네트워크 + nginx + 앱 + 가벼운 DB 조회**의 합이다.
// 데이터량과 무관한 경로라, 여기서 나오는 값이 이 환경의 "더 이상 못 내려가는 바닥"이다.
//
// k6 가 구간을 나눠주므로 함께 본다:
//   http_req_connecting  TCP 연결      → 네트워크
//   http_req_waiting     TTFB          → 서버 처리
//   http_req_receiving   응답 수신      → 네트워크 + 응답 크기
// 부하를 올렸을 때 waiting 이 늘면 앱, connecting·receiving 이 늘면 네트워크 쪽이다.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, expectOk } from './lib/config.js';

export const options = {
  stages: [
    { duration: '30s', target: 5 },
    { duration: '1m', target: 5 },
    { duration: '30s', target: 20 },
    { duration: '1m', target: 20 },
    { duration: '30s', target: 50 },
    { duration: '1m', target: 50 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  tags: { scenario: 's00-network-floor' },
};

export default function () {
  const res = http.get(`${BASE_URL}/api/categories`, { tags: { name: 'categories' } });
  expectOk(res, 'categories');
  check(res, { 'status 200': (r) => r.status === 200 });

  // 쉬지 않으면 VU 하나가 초당 수백 건을 쏜다. 그러면 VU 수가 "동시 사용자"라는 의미를
  // 잃고, 요청 제한에도 즉시 걸린다. 바닥값 측정도 사람의 호출 간격을 흉내내야 한다.
  sleep(0.5);
}
