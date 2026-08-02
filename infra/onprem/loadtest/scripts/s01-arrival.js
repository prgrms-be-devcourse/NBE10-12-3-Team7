// 1단계 — 사람이 몰린다. **가장 단순한 시나리오다.**
//
// 비로그인 사용자가 상품 목록 화면을 연다. 그게 전부다. 스크롤도 클릭도 없다.
// 여기서 무릎을 찾은 뒤, 다음 단계에서 로그인·동선을 하나씩 얹는다.
//
// **VU 1명 = 사람 1명이다.** 화면 진입 1회가 반복 1회이고, 그 안에서 API 가 동시에 나간다
// (프론트의 Promise.all 그대로). 요청 단위로 세면 "동시 100명"이 몇 명인지 알 수 없다.
//
// ⚠️ 실행 전 요청 제한을 풀어야 한다(s00 으로 동작을 확인한 뒤).
//    맥의 infra/onprem 에서: RATE_LIMIT_CAPACITY=100000 docker compose --env-file .env up -d app
//
// 실행(맥, 스택과 같은 호스트):
//   docker compose -f docker-compose.yml -f docker-compose.onprem.yml run --rm k6 run /scripts/s01-arrival.js
//   SMOKE=true  ...  # 동작 확인용, 35초
//   SOAK_VU=200 ...  # 무릎에서 10분 유지
// 노트북(LAN 너머, 사용자 실측)에서는 오버레이 없이:
//   docker compose run --rm k6 run /scripts/s01-arrival.js

import { sleep } from 'k6';
import {
  BASE_URL,
  BASELINE_MS,
  TREND_STATS,
  ALLOWED_RATIO,
  measureBaseline,
  recordRatio,
  ratioThresholds,
} from './lib/config.js';
import { screenProductList } from './lib/screens.js';
import { pickStages, thinkTime } from './lib/stages.js';

export const options = {
  stages: pickStages(),
  summaryTrendStats: TREND_STATS,
  // 절대 ms 가 아니라 **무부하 대비 배수**로 판정한다 — 이 환경의 절대 수치는 다른 곳과
  // 비교할 수 없다. 기준선은 아래 setup() 이 이 회차에 직접 잰다.
  thresholds: ratioThresholds(),
  tags: { scenario: 's01-arrival' },
};

/**
 * 부하를 걸기 전에 이 회차의 무부하를 직접 잰다. **판정의 기준선이다.**
 *
 * 왜 고정값을 안 쓰나: 호스트 curl 실측 13ms 를 그대로 쓰면 허용선이 39ms 인데, k6 컨테이너
 * 경로는 무부하가 이미 24ms 다 — 여유가 3배가 아니라 1.6배뿐이다. 그 상태로 재면 앱이 아직
 * 멀쩡한데도 VU 를 조금만 올리면 허용선을 넘어 **"무릎"으로 오독된다.**
 * 컨테이너 경유 고정비(약 7ms)는 부하와 무관한 덧셈인데 판정은 곱셈이라 여유를 갉아먹는다.
 */
export function setup() {
  const baseline = measureBaseline([
    { key: 'list', url: `${BASE_URL}/api/products?size=30` },
  ]);
  console.log(
    `이 회차 무부하 기준선: 목록 ${baseline.list.toFixed(1)}ms ` +
    `(허용선 ${(baseline.list * ALLOWED_RATIO).toFixed(1)}ms = ${ALLOWED_RATIO}배)`
  );
  return { baseline };
}

export default function (data) {
  const res = screenProductList();
  recordRatio(res, data.baseline.list);

  // 사람은 화면을 열고 바로 다음 행동을 하지 않는다. 이 값이 VU 를 실제 도착률로 바꾼다 —
  // VU 400 × (1÷4초) ≈ 초당 100명 진입. 빼면 VU 하나가 초당 수백 건을 쏘아
  // VU 수가 "동시 사용자"라는 의미를 잃는다.
  sleep(thinkTime());
}

export function handleSummary(data) {
  const base = data.setup_data && data.setup_data.baseline && data.setup_data.baseline.list;
  const ratio = data.metrics.latency_ratio && data.metrics.latency_ratio.values;
  const d = data.metrics.http_req_duration.values;
  const failed = data.metrics.http_req_failed.values.rate;
  const reqs = data.metrics.http_reqs.values;

  // setup 이 중단됐거나(429 등) 표본이 하나도 없으면 판정하지 않는다.
  if (!base || !ratio) {
    return {
      stdout: '\n⚠️ 기준선 또는 배수 표본이 없다 — 판정을 건너뛴다(setup 이 중단됐을 수 있다).\n',
      '/results/s01-arrival-summary.json': JSON.stringify(data, null, 2),
    };
  }

  const pathCost = base - BASELINE_MS.list;
  const pass = ratio['p(95)'] < ALLOWED_RATIO && failed < 0.01;

  const lines = [
    '',
    '── 1단계 판정 ──────────────────────────────────────',
    `  무부하 기준   목록 ${base.toFixed(1)} ms (이 회차 setup 에서 실측)`,
    `  참고          호스트 curl ${BASELINE_MS.list} ms → 경로 비용 ${pathCost >= 0 ? '+' : ''}${pathCost.toFixed(1)} ms`,
    `  허용선        무부하의 ${ALLOWED_RATIO} 배 (= ${(base * ALLOWED_RATIO).toFixed(1)} ms)`,
    '',
    `  배수 p95      ${ratio['p(95)'].toFixed(2)} 배   ← 판정은 이 값으로 한다`,
    `  배수 p99      ${ratio['p(99)'].toFixed(2)} 배`,
    `  응답 p95      ${d['p(95)'].toFixed(1)} ms`,
    `  응답 p99      ${d['p(99)'].toFixed(1)} ms`,
    `  실패율        ${(failed * 100).toFixed(2)} %`,
    `  처리량        ${reqs.rate.toFixed(1)} req/s (총 ${reqs.count})`,
    '',
    `  판정          ${pass ? '허용선 안' : '⚠️ 허용선을 넘었다'}`,
    '',
    '  ※ 배수는 목록 요청만 센다. 응답 p95·처리량은 categories 와 setup 의',
    '     기준선 측정까지 포함한 전체 요청 기준이라 서로 직접 비교하면 안 된다.',
    '  ※ 계단 전체의 합산값이다. 무릎이 어느 VU 에서 생겼는지는',
    '     Grafana 의 k6 대시보드에서 시간축으로 봐야 한다.',
    '',
  ].join('\n');

  return {
    stdout: lines,
    '/results/s01-arrival-summary.json': JSON.stringify(data, null, 2),
  };
}
