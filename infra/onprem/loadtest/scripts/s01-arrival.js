// 1단계 — 사람이 몰린다. **가장 단순한 시나리오다.**
//
// 비로그인 사용자가 상품 목록 화면을 연다. 그게 전부다. 스크롤도 클릭도 없다.
// 여기서 무릎을 찾은 뒤, 다음 단계에서 로그인·동선을 하나씩 얹는다.
//
// **VU 1명 = 사람 1명이다.** 화면 진입 1회가 반복 1회이고, 그 안에서 API 가 동시에 나간다
// (프론트의 Promise.all 그대로). 요청 단위로 세면 "동시 100명"이 몇 명인지 알 수 없다.
//
// ⚠️ 실행 전 요청 제한을 풀어야 한다(s00 으로 동작을 확인한 뒤).
//    맥에서: RATE_LIMIT_CAPACITY=100000 docker compose --env-file .env up -d app
//
// 실행:
//   docker compose run --rm k6 run /scripts/s01-arrival.js              # 계단 5→400, 약 15분
//   SMOKE=true docker compose run --rm k6 run /scripts/s01-arrival.js   # 동작 확인용, 35초
//   SOAK_VU=200 docker compose run --rm k6 run /scripts/s01-arrival.js  # 무릎에서 10분 유지

import { sleep } from 'k6';
import { BASELINE_MS, thresholdsFor, TREND_STATS } from './lib/config.js';
import { screenProductList } from './lib/screens.js';
import { pickStages, thinkTime } from './lib/stages.js';

export const options = {
  stages: pickStages(),
  summaryTrendStats: TREND_STATS,
  // 무부하 목록 응답 13ms 의 3배(약 40ms)를 넘으면 경보. 절대값이 아니라 배수로 본다 —
  // 이 환경의 절대 수치는 다른 곳과 비교할 수 없다.
  thresholds: thresholdsFor(BASELINE_MS.list),
  tags: { scenario: 's01-arrival' },
};

export default function () {
  screenProductList();

  // 사람은 화면을 열고 바로 다음 행동을 하지 않는다. 이 값이 VU 를 실제 도착률로 바꾼다 —
  // VU 400 × (1÷4초) ≈ 초당 100명 진입. 빼면 VU 하나가 초당 수백 건을 쏘아
  // VU 수가 "동시 사용자"라는 의미를 잃는다.
  sleep(thinkTime());
}

export function handleSummary(data) {
  const d = data.metrics.http_req_duration.values;
  const failed = data.metrics.http_req_failed.values.rate;
  const reqs = data.metrics.http_reqs.values;
  const limit = Math.round(BASELINE_MS.list * 3);

  const lines = [
    '',
    '── 1단계 판정 ──────────────────────────────────────',
    `  무부하 기준   목록 ${BASELINE_MS.list} ms (perf 볼륨 테스트 실측)`,
    `  허용선        p95 < ${limit} ms (기준의 3배)`,
    '',
    `  p95           ${d['p(95)'].toFixed(1)} ms`,
    `  p99           ${d['p(99)'].toFixed(1)} ms`,
    `  평균          ${d.avg.toFixed(1)} ms`,
    `  실패율        ${(failed * 100).toFixed(2)} %`,
    `  처리량        ${reqs.rate.toFixed(1)} req/s (총 ${reqs.count})`,
    '',
    `  판정          ${d['p(95)'] < limit && failed < 0.01 ? '허용선 안' : '⚠️ 허용선을 넘었다'}`,
    '',
    '  ※ 계단 전체의 합산값이다. 무릎이 어느 VU 에서 생겼는지는',
    '     Grafana 의 k6 대시보드에서 시간축으로 봐야 한다.',
    '',
  ].join('\n');

  return {
    stdout: lines,
    '/results/s01-arrival-summary.json': JSON.stringify(data, null, 2),
  };
}
