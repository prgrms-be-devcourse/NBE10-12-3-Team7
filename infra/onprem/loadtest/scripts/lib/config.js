// 공통 설정과 계약. 모든 시나리오가 여기를 거친다.

import exec from 'k6/execution';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost';

// 무부하 기준값(perf 볼륨 테스트 실측, 상품 10만 건 기준). 판정의 기준선이다.
// 이 환경의 절대 수치는 다른 곳과 비교할 수 없으므로 **무부하 대비 배수**로 판정한다.
export const BASELINE_MS = {
  list: 13,
  detail: 12,
  comments: 9,
};

// k6 는 기본으로 p99 를 계산하지 않는다. 시나리오마다 다른 통계를 내면 회차 비교가
// 어긋나므로 한 곳에 고정한다.
export const TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

// 몇 배까지 허용할지. 기본 3배.
export const ALLOWED_RATIO = Number(__ENV.ALLOWED_RATIO || 3);

export function thresholdsFor(baselineMs) {
  return {
    http_req_failed: ['rate<0.01'],
    http_req_duration: [`p(95)<${Math.round(baselineMs * ALLOWED_RATIO)}`],
  };
}

/**
 * 응답이 정상인지 확인하고, 아니면 **테스트를 즉시 중단한다.**
 *
 * 특히 429 — 앱에는 IP 당 요청 제한이 있다(기본 초당 6건). 거부 응답은 본문이 없어 아주 빨리
 * 돌아오므로, 그대로 기록하면 **"빨라졌다"로 잘못 남는다.** 볼륨 테스트에서 실제로 당했다.
 * 조용히 틀린 숫자가 표에 남는 것이 이 작업에서 가장 위험한 실패다.
 *
 * s00-ratelimit 만 예외다 — 그 시나리오는 429 를 기대하고 재므로 이 함수를 쓰지 않는다.
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
}
