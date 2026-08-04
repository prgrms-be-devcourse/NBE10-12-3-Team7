// 1단계 — 사람이 몰린다. **가장 단순한 시나리오다.**
//
// 비로그인 사용자가 상품 목록 화면을 연다. 그게 전부다. 스크롤도 클릭도 없다.
// 여기서 무릎을 찾은 뒤, 다음 단계에서 로그인·동선을 하나씩 얹는다.
//
// **계단은 도착률이다 — 초당 몇 명이 이 화면에 들어오는가.** VU 가 아니다.
// 화면 진입 1회가 반복 1회이고, 그 안에서 API 2건이 동시에 나간다(프론트의 Promise.all 그대로).
// 초당 100 진입이면 초당 200 요청이다.
//
// VU 계단을 쓰지 않는 이유: 결론이 think time 가정(우리가 정한 3~5초)에 통째로 의존하게 된다.
// 도착률로 재면 "초당 N 명까지 버틴다"가 가정 없이 나오고, 동시 사용자 환산은 나중에
// 원하는 think time 으로 계산해 덧붙일 수 있다. (stages.js 의 ARRIVAL_LADDER 주석 참고)
//
// ⚠️ 실행 전 요청 제한을 풀어야 한다(s00 으로 동작을 확인한 뒤).
//    맥의 infra/onprem 에서: RATE_LIMIT_CAPACITY=100000 docker compose --env-file .env up -d app
//
// 실행(맥, 스택과 같은 호스트):
//   docker compose -f docker-compose.yml -f docker-compose.onprem.yml run --rm k6 run /scripts/s01-arrival.js
//   SMOKE=true    ...  # 동작 확인용, 35초
//   SOAK_RATE=200 ...  # 무릎에서 찾은 도착률로 10분 유지
// 노트북(LAN 너머, 사용자 실측)에서는 오버레이 없이:
//   docker compose run --rm k6 run /scripts/s01-arrival.js

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
import { pickArrivalStages } from './lib/stages.js';

export const options = {
  scenarios: {
    arrival: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      stages: pickArrivalStages(),
      // 도착률을 지키려면 k6 가 VU 를 충분히 들고 있어야 한다. 응답이 느려질수록 같은
      // 도착률에 더 많은 VU 가 필요하다 — 모자라면 k6 가 목표 도착률을 못 채우고,
      // 그건 앱이 아니라 부하 생성기의 한계다(요약에 dropped_iterations 로 나온다).
      preAllocatedVUs: Number(__ENV.PRE_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 800),
    },
  },
  summaryTrendStats: TREND_STATS,
  // 절대 ms 가 아니라 **무부하 대비 배수**로 판정한다 — 이 환경의 절대 수치는 다른 곳과
  // 비교할 수 없다. 기준선은 아래 setup() 이 이 회차에 직접 잰다.
  // products_list 는 보고용 서브메트릭이다(기준선 측정과 categories 를 뺀 값).
  thresholds: ratioThresholds(['products_list']),
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

// 반복 1회 = 사람 1명이 상품 목록 화면에 한 번 들어오는 것. 그게 전부다.
// think time 을 넣지 않는다 — 다음 행동이 없고, 도착률 자체가 부하 모델이기 때문이다.
// 여기서 sleep 을 하면 VU 만 더 오래 붙잡아 같은 도착률에 더 많은 VU 가 필요해질 뿐이다.
export default function (data) {
  const res = screenProductList();
  recordRatio(res, data.baseline.list);
}

export function handleSummary(data) {
  const base = data.setup_data && data.setup_data.baseline && data.setup_data.baseline.list;
  const ratio = data.metrics.latency_ratio && data.metrics.latency_ratio.values;
  const failed = data.metrics.http_req_failed.values.rate;

  // 보고용은 **부하 구간의 목록 요청만** 본다. 전체 http_req_duration 에는 setup() 의
  // 기준선 측정(baseline_list)과 categories 가 섞여 있어 배수와 모집단이 달라진다.
  const listed = data.metrics['http_req_duration{name:products_list}'];
  const d = listed ? listed.values : data.metrics.http_req_duration.values;

  // 처리량은 요청이 아니라 **화면 진입**으로 센다 — 계단의 단위가 초당 진입이기 때문이다.
  const iters = data.metrics.iterations.values;

  // setup 이 중단됐거나(429 등) 표본이 하나도 없으면 판정하지 않는다.
  if (!base || !ratio) {
    return {
      stdout: '\n⚠️ 기준선 또는 배수 표본이 없다 — 판정을 건너뛴다(setup 이 중단됐을 수 있다).\n',
      '/results/s01-arrival-summary.json': JSON.stringify(data, null, 2),
    };
  }

  const pathCost = base - BASELINE_MS.list;
  const pass = ratio['p(95)'] < ALLOWED_RATIO && failed < 0.01;

  // k6 가 목표 도착률을 못 채우고 버린 반복. 0 이 아니면 그 구간은 **앱이 아니라 부하
  // 생성기의 한계**를 잰 것이라 판정에 쓸 수 없다. 맥 한 대에서 돌릴 때 특히 그렇다.
  const dropped = (data.metrics.dropped_iterations && data.metrics.dropped_iterations.values.count) || 0;

  const lines = [
    '',
    '── 1단계 판정 ──────────────────────────────────────',
    `  무부하 기준   목록 ${base.toFixed(1)} ms (이 회차 setup 에서 실측)`,
    `  참고          호스트 curl ${BASELINE_MS.list} ms → 경로 비용 ${pathCost >= 0 ? '+' : ''}${pathCost.toFixed(1)} ms`,
    `  허용선        무부하의 ${ALLOWED_RATIO} 배 (= ${(base * ALLOWED_RATIO).toFixed(1)} ms)`,
    '',
    `  배수 p95      ${ratio['p(95)'].toFixed(2)} 배   ← 판정은 이 값으로 한다`,
    `  배수 p99      ${ratio['p(99)'].toFixed(2)} 배`,
    `  목록 응답 p95 ${d['p(95)'].toFixed(1)} ms   (기준선·categories 제외)`,
    `  목록 응답 p99 ${d['p(99)'].toFixed(1)} ms`,
    `  실패율        ${(failed * 100).toFixed(2)} %`,
    `  소화한 진입   ${iters.rate.toFixed(1)} 건/초 (총 ${iters.count})`,
    `  버린 반복     ${dropped}${dropped > 0
      ? '  ⚠️ k6 가 목표 도착률을 못 채웠다 — 그 구간은 앱이 아니라 부하 생성기 한계다'
      : '  (목표 도착률을 전부 채웠다)'}`,
    '',
    `  판정          ${pass ? '허용선 안' : '⚠️ 허용선을 넘었다'}`,
    '',
    '  ※ 배수와 목록 응답은 같은 모집단(부하 구간의 목록 요청)이라 나란히 읽어도 된다.',
    '     기준선 측정 20건과 categories 는 태그로 걸러져 있다.',
    '  ※ 계단 전체의 합산값이다. 무릎이 초당 몇 진입에서 생겼는지는',
    '     Grafana 의 k6 대시보드에서 시간축으로 봐야 한다.',
    '',
  ].join('\n');

  return {
    stdout: lines,
    '/results/s01-arrival-summary.json': JSON.stringify(data, null, 2),
  };
}
