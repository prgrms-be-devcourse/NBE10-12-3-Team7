// 쓰기 시나리오 — 상품 등록. **동시에 쓰기가 몰릴 때 버티는가.**
//
// 지금까지(s01)는 전부 읽기였다. 이 시나리오만 행을 만든다.
//
// **층위 (a) — 등록 API 만 건다.** 실제 등록 화면은 이미지 업로드(multipart) → 등록 두 단계지만
// (frontend/src/components/ProductForm.tsx:180, 258), 여기서는 등록만 부른다.
// 업로드를 섞으면 무릎이 나와도 **DB 때문인지 RustFS 때문인지 구분할 수 없다.**
// 업로드까지 포함한 회차는 이 다음 주제다.
//
// ⚠️ 그래서 `imageUrls` 에는 **저장소에 실제로 없는 URL 문자열**을 넣는다.
//    ProductService.validateProductImages 가 개수만 검사하고 존재 여부는 보지 않아 통과한다.
//    실제 화면과 다른 지점이므로 회차 요약에 반드시 적는다.
//
// 계단은 `WRITE_LADDER` — 도착률은 읽기와 같지만 **유지가 1분**이다. 쓰기는 매 요청이 행을
// 만들어 회차 도중 데이터 크기가 변하기 때문이다(stages.js 주석 참고).
//
// 회차가 끝나면 `[load-write]` 마커로 정리한다:
//   ./setup/unload-write.sh
//
// 실행(맥):
//   ./scenarios/run.sh s02-product-create pool-20
//   SMOKE=true ./scenarios/run.sh s02-product-create wiring

import exec from 'k6/execution';
import {
  BASE_URL,
  TREND_STATS,
  ALLOWED_RATIO,
  measureBaseline,
  recordRatio,
  ratioThresholds,
} from './lib/config.js';
import { issueTokens, tokenFor } from './lib/auth.js';
import { actionProductCreate, buildProductPayload } from './lib/screens.js';
import { pickWriteStages } from './lib/stages.js';

const LOGIN_ACCOUNTS = Number(__ENV.LOGIN_ACCOUNTS || 300);

/**
 * 등록을 흩뿌릴 동네. 기본값은 `[load]` 데이터에서 상품 5,000건 이상을 가진 12개 동네다
 * (이 데이터셋은 이봉형이라 나머지 5,055개 동네는 1~9건뿐이다).
 * 한 동네에만 몰아 넣으면 그 동네의 인덱스에만 경합이 생겨 실제와 달라진다.
 */
const REGION_CODES = (__ENV.REGION_CODES ||
  '1111010100,1111010200,1111010300,1111010400,1111010500,1111010600,' +
  '1111011100,1111011200,1111011300,1111011400,1111011500,1111011600').split(',');

const CATEGORY_IDS = (__ENV.CATEGORY_IDS || '1,2,3,4,5,6,7').split(',').map(Number);

export const options = {
  // 응답 본문을 버린다. **이 시나리오는 본문을 쓰지 않는다** — expectOk 는 상태 코드만 보고
  // 판정은 res.timings 만 쓴다. 버리면 k6 의 파싱·할당·GC 가 사라져 같은 CPU 로 더 높은
  // 도착률을 만들 수 있다(초당 600 진입이면 9.4KB × 1,200 = 11MB/s 를 파싱하고 있었다).
  // 맥 한 대에서 앱과 CPU 를 나눠 쓰므로 부하 생성기를 가볍게 하는 것이 곧 측정 여유가 된다.
  //
  // ⚠️ 본문이 필요한 요청은 개별로 되살린다 — lib/auth.js 의 로그인(accessToken 추출)이
  //    responseType: 'text' 를 명시하는 이유다. s00-ratelimit 은 본문을 검증하므로 이 옵션을 쓰지 않는다.
  discardResponseBodies: true,
  scenarios: {
    write: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      stages: pickWriteStages(),
      preAllocatedVUs: Number(__ENV.PRE_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 800),
    },
  },
  summaryTrendStats: TREND_STATS,
  thresholds: ratioThresholds(['product_create']),
  tags: { scenario: 's02-product-create' },
};

export function setup() {
  const tokens = issueTokens(LOGIN_ACCOUNTS);
  console.log(`로그인 토큰 ${tokens.length}개 발급`);

  // 무부하 등록 비용. 분모와 분자가 같은 요청이어야 배수가 의미를 갖는다.
  const baseline = measureBaseline([{
    key: 'create',
    method: 'POST',
    url: `${BASE_URL}/api/products`,
    body: JSON.stringify(buildProductPayload(0, CATEGORY_IDS, REGION_CODES)),
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokens[0]}` },
    okStatuses: [201],
  }]);

  console.log(
    `이 회차 무부하 기준선: 등록 ${baseline.create.toFixed(1)}ms ` +
    `(허용선 ${(baseline.create * ALLOWED_RATIO).toFixed(1)}ms = ${ALLOWED_RATIO}배)`
  );
  return { baseline, judged: baseline.create, tokens };
}

// 반복 1회 = 사람 1명이 상품 하나를 등록한다.
export default function (data) {
  const res = actionProductCreate(
    tokenFor(data.tokens),
    buildProductPayload(exec.scenario.iterationInTest, CATEGORY_IDS, REGION_CODES)
  );
  recordRatio(res, data.judged);
}

export function handleSummary(data) {
  const base = data.setup_data && data.setup_data.baseline && data.setup_data.baseline.create;
  const ratio = data.metrics.latency_ratio && data.metrics.latency_ratio.values;
  const failed = data.metrics.http_req_failed.values.rate;
  const created = data.metrics['http_req_duration{name:product_create}'];
  const d = created ? created.values : data.metrics.http_req_duration.values;
  const iters = data.metrics.iterations.values;

  if (!base || !ratio) {
    return {
      stdout: '\n⚠️ 기준선 또는 배수 표본이 없다 — 판정을 건너뛴다(setup 이 중단됐을 수 있다).\n',
      '/results/s02-product-create-summary.json': JSON.stringify(data, null, 2),
    };
  }

  const dropped = (data.metrics.dropped_iterations && data.metrics.dropped_iterations.values.count) || 0;
  const pass = ratio['p(95)'] < ALLOWED_RATIO && failed < 0.01;

  const lines = [
    '',
    '── 쓰기 판정 ───────────────────────────────────────',
    `  측정 대상     상품 등록 (POST /api/products) — 업로드 제외, 층위 (a)`,
    `  무부하 기준   등록 ${base.toFixed(1)} ms (이 회차 setup 에서 실측)`,
    `  허용선        무부하의 ${ALLOWED_RATIO} 배 (= ${(base * ALLOWED_RATIO).toFixed(1)} ms)`,
    '',
    `  배수 p95      ${ratio['p(95)'].toFixed(2)} 배   ← 판정은 이 값으로 한다`,
    `  배수 p99      ${ratio['p(99)'].toFixed(2)} 배`,
    `  등록 응답 p95 ${d['p(95)'].toFixed(1)} ms`,
    `  등록 응답 p99 ${d['p(99)'].toFixed(1)} ms`,
    `  실패율        ${(failed * 100).toFixed(2)} %`,
    `  소화한 등록   ${iters.rate.toFixed(1)} 건/초 (총 ${iters.count})`,
    `  버린 반복     ${dropped}${dropped > 0
      ? '  ⚠️ k6 가 목표 도착률을 못 채웠다 — 그 구간은 부하 생성기 한계다'
      : '  (목표 도착률을 전부 채웠다)'}`,
    '',
    `  판정          ${pass ? '허용선 안' : '⚠️ 허용선을 넘었다'}`,
    '',
    `  ※ 이 회차가 만든 상품은 약 ${iters.count} 건이다. 회차 도중 데이터가 늘어난다는 뜻이므로`,
    '     후반 계단은 앞 계단과 다른 데이터 크기에서 잰 값이다. 끝나고 ./setup/unload-write.sh 로 정리한다.',
    '  ※ imageUrls 는 저장소에 없는 문자열이다. 업로드(RustFS)는 이 회차의 부하 경로가 아니다.',
    '',
  ].join('\n');

  return {
    stdout: lines,
    '/results/s02-product-create-summary.json': JSON.stringify(data, null, 2),
  };
}
