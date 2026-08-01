// 시나리오 02 — 실제 사용자 행동으로 동시성 한계 찾기. **이 폴더의 주 시나리오다.**
//
// 하나의 엔드포인트만 때리면 실제 부하 모양이 아니다. 사용자는 목록을 보고, 스크롤하고,
// 몇 개를 눌러보고, 댓글을 읽는다. 그 비율에 맞춰 섞는다.
//
//   40%  목록 진입 (동네 필터)   30%  스크롤    20%  상세 클릭    10%  댓글 열람
//
// **응답이 작은 것만 넣는다.** 관리자 상품 목록은 수백 MB 라, 여러 VU 가 동시에 받으면
// 측정하는 것이 앱이 아니라 네트워크 대역폭이 된다.
//
// ⚠️ 실행 전 요청 제한을 풀어야 한다(s01 로 동작을 확인한 뒤).
//    맥에서: RATE_LIMIT_CAPACITY=100000 docker compose --env-file .env up -d app
//    풀지 않으면 초당 6건에서 전부 429 가 되어 앱이 아니라 제한기를 측정하게 된다.
//
// ⚠️ s00-network-floor 를 먼저 돌려 바닥값을 알고 시작한다. 그게 없으면 "느려졌다"가
//    앱 때문인지 네트워크 때문인지 갈라낼 수 없다.

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { BASE_URL, THRESHOLDS, discoverTargets, expectOk } from './lib/config.js';

export const options = {
  // 계단식으로 올린다. 각 단계를 2분 유지해 안정된 구간에서 값을 얻는다 —
  // 램프업 중의 값은 과도기라 판정에 쓰지 않는다.
  stages: [
    { duration: '30s', target: 5 },
    { duration: '2m', target: 5 },
    { duration: '30s', target: 10 },
    { duration: '2m', target: 10 },
    { duration: '30s', target: 20 },
    { duration: '2m', target: 20 },
    { duration: '30s', target: 40 },
    { duration: '2m', target: 40 },
    { duration: '30s', target: 80 },
    { duration: '2m', target: 80 },
    { duration: '30s', target: 0 },
  ],
  thresholds: THRESHOLDS,
  tags: { scenario: 's02-browse' },
};

export function setup() {
  const t = discoverTargets();
  console.log(`대상 탐색 완료 — 상품 ${t.productIds.length}개, 동네 ${t.regionCode}, 커서 ${t.cursor}`);
  return t;
}

export default function (t) {
  const pick = Math.random();
  const productId = t.productIds[Math.floor(Math.random() * t.productIds.length)];

  if (pick < 0.4) {
    group('목록 진입', () => {
      const res = http.get(`${BASE_URL}/api/products?size=30&regionCodes=${t.regionCode}`,
        { tags: { name: 'list_region' } });
      expectOk(res, 'list_region');
      check(res, { 'list_region 200': (r) => r.status === 200 });
    });
  } else if (pick < 0.7) {
    group('스크롤', () => {
      const res = http.get(`${BASE_URL}/api/products?size=30&cursor=${t.cursor}`,
        { tags: { name: 'list_deep' } });
      expectOk(res, 'list_deep');
      check(res, { 'list_deep 200': (r) => r.status === 200 });
    });
  } else if (pick < 0.9) {
    group('상세 클릭', () => {
      const res = http.get(`${BASE_URL}/api/products/${productId}`, { tags: { name: 'detail' } });
      expectOk(res, 'detail');
      check(res, { 'detail 200': (r) => r.status === 200 });
    });
  } else {
    group('댓글 열람', () => {
      const res = http.get(`${BASE_URL}/api/products/${productId}/comments`,
        { tags: { name: 'comments' } });
      expectOk(res, 'comments');
      check(res, { 'comments 200': (r) => r.status === 200 });
    });
  }

  // 사람은 요청을 쉬지 않고 던지지 않는다. 이걸 빼면 VU 수가 실제 동시 사용자 수와
  // 전혀 다른 의미가 된다(VU 5 가 초당 수백 건을 쏘게 된다).
  sleep(Math.random() * 2 + 0.5);
}
