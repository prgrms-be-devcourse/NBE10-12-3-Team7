// 3단계 — 작은 응답으로 트래픽 상한. **네트워크가 아니라 앱을 재기 위한 회차다.**
//
// 왜 이 회차가 생겼나(2026-08-04 노트북 원격 실측):
//   이 Wi-Fi 의 실효 대역폭이 iperf3 로 1.91 MB/s 로 나왔다(문서 가정 4.5 MB/s 의 42%).
//   동네필터 응답은 9.6KB 라 대역폭 상한이 **초당 198건**인데, 앱 무릎은 초당 200~230 이라
//   둘이 겹친다 — 그 시나리오를 돌리면 앱이 아니라 Wi-Fi 를 재게 된다(REMOTE-RUN.md 판정표).
//
// 그래서 **응답을 가능한 작게** 만든다. `/api/products?size=1` 은 449B 라 대역폭 상한이
// 초당 4,259건으로 뛴다. 앱의 가벼운-조회 상한(커넥션풀 10 × size=1 조회 ~5ms ≈ 초당 2,000)이
// 그 아래라, **네트워크가 벽이 되기 전에 앱(nginx·Tomcat·커넥션풀)이 먼저 무너지는 지점**을
// 볼 수 있다. 지금까지 5개 회차에서 못 본 계층이 처음 드러날 수 있다(REMOTE-RUN.md 다음 회차 후보 1).
//
// ⚠️ **이것은 사용자 여정이 아니라 합성 부하다.** s01·s02 는 프론트의 화면을 재현했지만,
//    이 회차는 "앱의 순수 처리량 상한이 어디냐"만 묻는다. 그래서 화면이 아니라 단일 프로브다.
//
// **계단 단위가 s01 과 다르다 — 초당 "요청"이다.** s01 은 화면 진입 1회에 API 2건이 나갔지만,
// 여기서는 프로브 1건이 요청 1건이라 target 이 곧 초당 요청 수다(stages.js THROUGHPUT_LADDER).
//
// 실행 전 요청 제한이 풀려 있어야 한다(s00 으로 확인). 노트북(LAN 너머)에서:
//   MSYS_NO_PATHCONV=1 ./scenarios/run.sh s03-throughput-ceiling laptop-min-response
//   SMOKE=true ...   # 동작 확인용, 35초
//
// **판정은 요약 숫자만으로 끝나지 않는다.** 어느 벽에 부딪혔는지는 Grafana 서명으로 가른다:
//   · hikaricp_connections_pending 급증 + active=10 고정 → 앱(커넥션 풀)이 벽 ← 우리가 찾는 것
//   · 네트워크 행이 iperf3 실측치(1.91MB/s)에 붙음        → Wi-Fi 가 벽 (그 구간 폐기)
//   · dropped_iterations 오르는데 네트워크·pending 둘 다 여유 → 노트북 k6 가 벽 (MAX_VUS 조정)

import {
  BASE_URL,
  TREND_STATS,
  ALLOWED_RATIO,
  measureBaseline,
  recordRatio,
  ratioThresholds,
} from './lib/config.js';
import { probeProductsMin } from './lib/screens.js';
import { pickThroughputStages } from './lib/stages.js';

export const options = {
  scenarios: {
    throughput: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      stages: pickThroughputStages(),
      // 도착률(초당 요청)을 지키려면 k6 가 VU 를 충분히 들고 있어야 한다. 응답이 느려질수록
      // 같은 도착률에 더 많은 VU 가 필요하다 — 모자라면 목표를 못 채우고 dropped_iterations 로
      // 나오는데, 그건 앱이 아니라 노트북 k6 의 한계다. 그래서 넉넉히 잡되(1500),
      // 이 수를 넘어야 도착률이 유지되는 구간은 이미 앱이 무너진 뒤라 판정에서 뺀다.
      preAllocatedVUs: Number(__ENV.PRE_VUS || 300),
      maxVUs: Number(__ENV.MAX_VUS || 1500),
    },
  },
  summaryTrendStats: TREND_STATS,
  // 절대 ms 가 아니라 무부하 대비 배수로 판정한다(config.js 참고). products_min 은 보고용
  // 서브메트릭 — setup() 의 기준선 측정을 뺀 부하 구간의 프로브 요청만 본다.
  thresholds: ratioThresholds(['products_min']),
  tags: { scenario: 's03-throughput' },
};

/**
 * 부하 전에 이 회차의 무부하를 직접 잰다. **판정의 분모다.**
 * size=1 경로의 무부하이므로 s01(size=30)과 분모가 다르다 — 두 회차의 절대 ms 를 섞으면 안 된다.
 */
export function setup() {
  const probes = [{ key: 'min', url: `${BASE_URL}/api/products?size=1` }];
  const baseline = measureBaseline(probes);
  console.log(
    `이 회차 무부하 기준선: size=1 ${baseline.min.toFixed(1)}ms ` +
    `(판정 허용선 ${(baseline.min * ALLOWED_RATIO).toFixed(1)}ms = ${ALLOWED_RATIO}배)`
  );
  return { baseline, judged: baseline.min };
}

// 반복 1회 = 프로브 요청 1건. think time 없다 — 도착률 자체가 부하 모델이고, 이 회차는
// 사람의 화면 체류가 아니라 앱의 순수 처리량을 재기 때문이다.
export default function (data) {
  const res = probeProductsMin();
  recordRatio(res, data.judged);
}

export function handleSummary(data) {
  const ratio = data.metrics.latency_ratio && data.metrics.latency_ratio.values;
  const failed = data.metrics.http_req_failed.values.rate;

  // 보고용은 부하 구간의 프로브 요청만 본다. 전체 http_req_duration 에는 setup() 의
  // 기준선 측정(baseline_min)이 섞여 배수와 모집단이 달라진다.
  const probed = data.metrics['http_req_duration{name:products_min}'];
  const d = probed ? probed.values : data.metrics.http_req_duration.values;

  // 처리량은 초당 요청으로 센다 — 이 회차 계단의 단위가 초당 요청이기 때문이다.
  const iters = data.metrics.iterations.values;
  const judged = (data.setup_data && data.setup_data.judged);

  if (!judged || !ratio) {
    return {
      stdout: '\n⚠️ 기준선 또는 배수 표본이 없다 — 판정을 건너뛴다(setup 이 중단됐을 수 있다).\n',
      '/results/s03-throughput-ceiling-summary.json': JSON.stringify(data, null, 2),
    };
  }

  // k6 가 목표 도착률을 못 채우고 버린 반복. 0 이 아니면 그 구간은 노트북 k6 가 한계거나
  // (MAX_VUS 부족) 앱이 이미 무너진 뒤다 — 어느 쪽인지는 Grafana 로 가른다.
  const dropped = (data.metrics.dropped_iterations && data.metrics.dropped_iterations.values.count) || 0;
  const pass = ratio['p(95)'] < ALLOWED_RATIO && failed < 0.01;

  const lines = [
    '',
    '── 3단계 판정 — 처리량 상한(작은 응답) ─────────────',
    '  측정 대상     비로그인 · /api/products?size=1 (449B 프로브)',
    `  무부하 기준   size=1 ${judged.toFixed(1)} ms (이 회차 setup 에서 실측)`,
    `  허용선        무부하의 ${ALLOWED_RATIO} 배 (= ${(judged * ALLOWED_RATIO).toFixed(1)} ms)`,
    '',
    `  배수 p95      ${ratio['p(95)'].toFixed(2)} 배   ← 판정은 이 값으로 한다`,
    `  배수 p99      ${ratio['p(99)'].toFixed(2)} 배`,
    `  프로브 p95    ${d['p(95)'].toFixed(1)} ms   (기준선 측정 제외)`,
    `  프로브 p99    ${d['p(99)'].toFixed(1)} ms`,
    `  실패율        ${(failed * 100).toFixed(2)} %`,
    `  소화한 처리량 ${iters.rate.toFixed(1)} req/s (총 ${iters.count})`,
    `  버린 반복     ${dropped}${dropped > 0
      ? '  ⚠️ 목표 도착률을 못 채운 구간이 있다 — Grafana 로 앱 붕괴인지 노트북 k6 한계인지 가른다'
      : '  (목표 도착률을 전부 채웠다)'}`,
    '',
    `  판정          ${pass ? '허용선 안' : '⚠️ 허용선을 넘었다'}`,
    '',
    '  ※ 요약은 계단 전체의 합산값이다. **무릎이 초당 몇 요청에서 생겼는지, 그리고 그것이',
    '     앱 벽인지 Wi-Fi 벽인지 노트북 벽인지는 Grafana 시간축 + 아래 서명으로 판별한다:**',
    '       · hikaricp_pending 급증 + active=10 고정  → 앱(커넥션 풀)이 벽  ← 이 회차의 목표',
    '       · 네트워크 행이 iperf3 실측(1.91MB/s)에 붙음 → Wi-Fi 가 벽 (그 구간 폐기)',
    '       · dropped_iterations 오르는데 둘 다 여유    → 노트북 k6 가 벽 (MAX_VUS↑ 재실행)',
    '',
  ].join('\n');

  return {
    stdout: lines,
    '/results/s03-throughput-ceiling-summary.json': JSON.stringify(data, null, 2),
  };
}
