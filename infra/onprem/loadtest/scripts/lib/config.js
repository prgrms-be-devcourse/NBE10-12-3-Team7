// 시나리오 공통 설정. 모든 스크립트가 여기서 대상과 임계값을 가져온다.
//
// 부하를 거는 노트북에는 DB 가 없다. 그래서 상품 id·지역 코드를 **하드코딩하지 않고**
// setup() 단계에서 목록 API 응답에서 뽑아 쓴다 — 데이터가 바뀌어도 스크립트를 안 고쳐도 된다.

import http from 'k6/http';
import exec from 'k6/execution';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost';

// 이 호스트의 절대 수치는 다른 환경과 비교할 수 없다(Docker Desktop 의 유저스페이스 네트워킹).
// 임계값은 "여기서 이 정도면 이상하다"를 잡는 경보선이지 성능 목표가 아니다.
export const THRESHOLDS = {
  http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: false }],
  http_req_duration: ['p(95)<1000', 'p(99)<3000'],
};

/**
 * 목록 API 한 번으로 이후 시나리오가 쓸 대상을 모은다.
 * 각 VU 가 아니라 실행당 1회만 돈다(k6 의 setup 규약).
 */
export function discoverTargets() {
  // http 는 파일 상단에서 import 한다. k6 의 require() 는 init 단계(전역 스코프)에서만
  // 쓸 수 있어 함수 안에서 부르면 실행이 통째로 죽는다(실측 확인).
  const res = http.get(`${BASE_URL}/api/products?size=30`);
  if (res.status !== 200) {
    throw new Error(`대상 탐색 실패: /api/products 가 ${res.status}. 스택이 떠 있는지, BASE_URL(${BASE_URL})이 맞는지 확인할 것`);
  }
  const body = res.json();
  const items = (body.data && body.data.items) || [];
  if (items.length === 0) {
    throw new Error('상품이 0건이다. 맥에서 setup/load-data.sh 로 데이터를 먼저 적재할 것');
  }

  // 가장 많이 등장한 지역 코드를 고른다 — 실제 사용자가 몰리는 동네를 재현하기 위해서다.
  const counts = {};
  items.forEach((p) => { counts[p.regionCode] = (counts[p.regionCode] || 0) + 1; });
  const regionCode = Object.keys(counts).sort((a, b) => counts[b] - counts[a])[0];

  return {
    productIds: items.map((p) => p.productId),
    regionCode,
    cursor: body.data.nextCursor,
  };
}

/**
 * 응답이 정상인지 확인하고, 429 를 만나면 **테스트를 즉시 중단한다.**
 *
 * 앱에는 IP 당 요청 제한이 걸려 있다(기본 초당 6건). 넘기면 429 가 오는데, 거부 응답은
 * 본문이 없어 아주 빨리 돌아온다 — 그대로 기록하면 **"빨라졌다"로 잘못 남는다.**
 * 볼륨 테스트에서 실제로 한 번 당했고, 그래서 조용히 넘어가지 않고 중단한다.
 *
 * s01-ratelimit 만 예외다 — 그 시나리오는 429 를 기대하고 재는 것이라 이 함수를 쓰지 않는다.
 */
export function expectOk(res, name) {
  if (res.status === 429) {
    exec.test.abort(
      `${name} 이 429 다. 요청 제한(IP 당 기본 초당 6건)에 걸렸다 — 이 상태로 측정하면 ` +
      `앱이 아니라 제한기를 재게 된다. 맥에서 제한을 올리고 다시 실행할 것:\n` +
      `  RATE_LIMIT_CAPACITY=100000 docker compose --env-file .env up -d app`
    );
  }
  if (res.status !== 200) {
    exec.test.abort(`${name} 이 HTTP ${res.status}. 측정을 중단한다.`);
  }
  return true;
}
