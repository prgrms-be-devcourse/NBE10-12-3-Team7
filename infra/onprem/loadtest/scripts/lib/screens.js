// 화면 = API 묶음. **이 파일이 이 폴더의 핵심이다.**
//
// 시나리오는 화면을 조합할 뿐, API 를 직접 부르지 않는다. 이유가 셋이다.
//
//  1. **VU 1명 = 사람 1명**이 되게 하려면 세는 단위가 요청이 아니라 화면이어야 한다.
//     사람이 상품 목록을 열면 API 가 4개 동시에 나간다. 요청으로 세면 "동시 50명"이
//     실제로 몇 명인지 알 수 없다.
//  2. 프론트의 호출 묶음을 그대로 옮겨둔다. 화면이 바뀌면 여기만 고치면 모든 시나리오가 따라온다.
//  3. 나중에 동선 시나리오를 짤 때 화면을 이어붙이기만 하면 된다.
//
// 각 화면 함수는 프론트 코드에서 확인한 호출을 그대로 재현한다 — 출처를 주석에 적는다.

import http from 'k6/http';
import { expectOk } from './config.js';
import { BASE_URL } from './config.js';

/**
 * 상품 목록 화면 (`/products`) — 비로그인.
 * 출처: frontend/src/app/products/page.tsx 의 Promise.all + fetchProductPage
 * 로그인하지 않으면 categories 와 목록만 나간다.
 */
export function screenProductList(opts = {}) {
  const size = opts.size || 30;
  const region = opts.regionCode ? `&regionCodes=${opts.regionCode}` : '';
  const cursor = opts.cursor ? `&cursor=${opts.cursor}` : '';

  // 프론트가 Promise.all 로 동시에 부르므로 batch 로 재현한다.
  // 순차로 부르면 실제보다 부하가 얕고 응답시간도 다르게 나온다.
  const res = http.batch([
    ['GET', `${BASE_URL}/api/categories`, null, { tags: { name: 'categories' } }],
    ['GET', `${BASE_URL}/api/products?size=${size}${region}${cursor}`, null, { tags: { name: 'products_list' } }],
  ]);

  expectOk(res[0], 'categories');
  expectOk(res[1], 'products_list');
  return res[1];
}

/**
 * 상품 상세 화면 (`/products/{id}`) — 비로그인.
 * 출처: frontend/src/app/products/[id]/page.tsx
 * 로그인 상태면 favorites·chat-rooms 가 더 나가지만, 비로그인 시나리오에서는 이 셋이다.
 */
export function screenProductDetail(productId) {
  const res = http.batch([
    ['GET', `${BASE_URL}/api/products/${productId}`, null, { tags: { name: 'product_detail' } }],
    ['GET', `${BASE_URL}/api/products/${productId}/comments`, null, { tags: { name: 'product_comments' } }],
    ['GET', `${BASE_URL}/api/categories`, null, { tags: { name: 'categories' } }],
  ]);

  expectOk(res[0], 'product_detail');
  expectOk(res[1], 'product_comments');
  expectOk(res[2], 'categories');
  return res[0];
}

/**
 * 목록 응답에서 다음 행동에 필요한 값을 꺼낸다.
 * 상품 id 를 하드코딩하지 않기 위해서다 — 데이터를 다시 적재하면 id 가 바뀐다
 * (perf 에서 id 를 박아뒀다가 404 를 맞은 적이 있다).
 */
export function pickFromList(res) {
  const body = res.json();
  const items = (body.data && body.data.items) || [];
  if (items.length === 0) {
    return { productIds: [], regionCode: null, cursor: null };
  }
  const counts = {};
  items.forEach((p) => { counts[p.regionCode] = (counts[p.regionCode] || 0) + 1; });
  return {
    productIds: items.map((p) => p.productId),
    regionCode: Object.keys(counts).sort((a, b) => counts[b] - counts[a])[0],
    cursor: body.data.nextCursor,
  };
}
