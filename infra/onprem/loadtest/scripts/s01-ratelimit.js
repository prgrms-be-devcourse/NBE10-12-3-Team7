// 시나리오 01 — 요청 제한이 실제로 막는가.
//
// **제한을 푸는 게 아니라 확인하는 단계다.** 앱에는 IP 당 슬라이딩 윈도우 제한이 걸려 있다
// (기본 10초당 60건 = 초당 6건, `RateLimitFilter`). 노트북 한 대는 IP 하나이므로 이 벽에
// 먼저 부딪힌다 — 모르고 부하를 올리면 앱이 아니라 **제한기를 측정**하게 된다.
//
// 여기서 확인할 것:
//   1. 제한이 실제로 동작하는가 (429 가 나오는가)
//   2. 어느 지점에서 잘리는가 (설정값과 맞는가)
//   3. 통과한 요청은 정상인가 (제한기가 정상 요청까지 망가뜨리지 않는가)
//
// 이 시나리오가 통과하면 이후 시나리오에서 `RATE_LIMIT_CAPACITY` 를 올리는 근거가 된다.
// 방어 장치가 의도대로 동작한다는 것 자체가 기록할 가치가 있다.
//
// 실행:  docker compose run --rm k6 run /scripts/s01-ratelimit.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { BASE_URL } from './lib/config.js';

const rateLimited = new Counter('rate_limited_429');
const limitedRate = new Rate('rate_limited_ratio');

// 도착률을 고정한다(VU 수가 아니라 초당 요청 수를 직접 정한다).
// 제한이 초당 6건이므로 20건을 걸면 대략 70% 가 잘려야 정상이다.
export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.TARGET_RPS || 20),
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 20,
      maxVUs: 50,
    },
  },
  // 429 는 여기서 "실패"가 아니라 **기대하는 동작**이다. 기본 임계값을 쓰지 않는다.
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(95)<1000'],
  },
  tags: { scenario: 's01-ratelimit' },
};

export default function () {
  const res = http.get(`${BASE_URL}/api/products?size=30`, {
    tags: { name: 'products_list' },
    // 429 를 오류로 세지 않도록 기대 상태를 넓힌다.
    responseCallback: http.expectedStatuses(200, 429),
  });

  const limited = res.status === 429;
  rateLimited.add(limited ? 1 : 0);
  limitedRate.add(limited);

  check(res, {
    '200 또는 429 만 나온다': (r) => r.status === 200 || r.status === 429,
    '통과한 요청은 정상 본문': (r) => r.status !== 200 || r.body.includes('items'),
  });
}

export function handleSummary(data) {
  const total = data.metrics.http_reqs.values.count;
  const limited = (data.metrics.rate_limited_429 && data.metrics.rate_limited_429.values.count) || 0;
  const pct = total ? ((limited / total) * 100).toFixed(1) : '0';
  const lines = [
    '',
    '── 요청 제한 판정 ────────────────────────────────',
    `  총 요청      ${total}`,
    `  429 로 차단  ${limited} (${pct}%)`,
    `  판정         ${limited > 0 ? '제한이 동작한다' : '⚠️ 429 가 하나도 없다 — 제한이 꺼져 있거나 목표 RPS 가 너무 낮다'}`,
    '',
  ].join('\n');
  return { stdout: lines };
}
