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

import http from 'k6/http';
import exec from 'k6/execution';
import {
  BASE_URL,
  BASELINE_MS,
  TREND_STATS,
  ALLOWED_RATIO,
  measureBaseline,
  recordRatio,
  ratioThresholds,
  expectOk,
} from './lib/config.js';
import { screenProductList, screenProductListLoggedIn } from './lib/screens.js';
import { pickArrivalStages } from './lib/stages.js';

/**
 * 동네 필터 회차 스위치. 값을 주면 목록 조회에 `regionCodes` 가 붙는다.
 *
 * 1회차(필터 없음)와 **딱 이것 하나만** 달라야 두 곡선의 차이를 필터 탓으로 돌릴 수 있다.
 * 그래서 시나리오 파일을 새로 만들지 않고 같은 파일에 스위치를 뒀다 — 파일이 갈리면
 * 계단·think time·판정 기준이 조금씩 어긋나고, 그러면 비교가 성립하지 않는다.
 *
 * 실제 화면에서는 로그인 + 동네 설정한 사용자만 이 요청을 보낸다. 여기서는 같은 HTTP 요청을
 * 재현할 뿐이라 **인증 오버헤드는 빠져 있다** — F-01 은 DB 비용 문제라 오히려 격리된다.
 */
const REGION_CODE = __ENV.REGION_CODE || '';

/**
 * 로그인 회차 스위치(3회차). `LOGIN=true` 면 시드 계정으로 미리 토큰을 받아 로그인 화면을 연다.
 *
 * 회원가입은 부하 경로에 넣지 않는다 — 이메일 인증이 선행 필수라 k6 로는 불가능하고(인증 코드가
 * 메일로만 간다), 사용자당 평생 1회라 부하 대상도 아니다. 대신 `10-seed.sql` 이 만들어 둔
 * 계정 300개를 쓴다.
 *
 * **비밀번호는 코드에 넣지 않는다.** 이 리포는 public 이고, 시드 계정 비밀번호는 관리자 계정
 * 해시를 복사한 것이라 그대로 적으면 이미 있는 노출을 한 번 더 늘리게 된다. `.env`(gitignore)
 * 에서만 읽는다.
 */
const LOGIN = __ENV.LOGIN === 'true';
const LOGIN_PASSWORD = __ENV.LOGIN_PASSWORD || '';
const LOGIN_ACCOUNTS = Number(__ENV.LOGIN_ACCOUNTS || 300);

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
 * 시드 계정으로 미리 로그인해 토큰을 모은다. 로그인 1회가 bcrypt 때문에 약 90ms 라
 * 300개면 약 30초 걸린다 — 그동안 앱이 워밍업되는 것은 기준선에 오히려 유리하다.
 * **로그인 자체는 부하 구간에 넣지 않는다.** 이번에 재려는 것은 로그인한 사용자의 화면 비용이지
 * 로그인 처리 비용이 아니다.
 */
function issueTokens(count) {
  const tokens = [];
  for (let i = 1; i <= count; i++) {
    const email = `load-${String(i).padStart(6, '0')}@loadtest.local`;
    const res = http.post(
      `${BASE_URL}/api/auth/login`,
      JSON.stringify({ email, password: LOGIN_PASSWORD }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup_login' } }
    );
    expectOk(res, `login ${email}`);
    const token = res.json('data.accessToken');
    if (!token) exec.test.abort(`${email} 로그인 응답에 accessToken 이 없다.`);
    tokens.push(token);
  }
  return tokens;
}

/**
 * 부하를 걸기 전에 이 회차의 무부하를 직접 잰다. **판정의 기준선이다.**
 *
 * 왜 고정값을 안 쓰나: 호스트 curl 실측 13ms 를 그대로 쓰면 허용선이 39ms 인데, k6 컨테이너
 * 경로는 무부하가 이미 24ms 다 — 여유가 3배가 아니라 1.6배뿐이다. 그 상태로 재면 앱이 아직
 * 멀쩡한데도 도착률을 조금만 올려도 허용선을 넘어 **"무릎"으로 오독된다.**
 */
export function setup() {
  let tokens = [];
  if (LOGIN) {
    if (!LOGIN_PASSWORD) {
      exec.test.abort(
        'LOGIN=true 인데 LOGIN_PASSWORD 가 없다. loadtest/.env 에 시드 계정 비밀번호를 넣을 것 ' +
        '(코드에 적지 않는다 — 이 리포는 public 이다).'
      );
    }
    tokens = issueTokens(LOGIN_ACCOUNTS);
    console.log(`로그인 토큰 ${tokens.length}개 발급`);
  }

  // 판정 대상인 `/api/products` 는 **로그인 여부와 무관하게 인증 헤더가 붙지 않는다**
  // (프론트가 apiFetch 가 아니라 fetch 로 부른다). 그래서 기준선 측정도 1회차와 똑같다 —
  // 같은 요청, 같은 분모다. 3회차가 재는 것은 "같은 요청이 화면당 API 2건 늘어난 상태에서
  // 얼마나 느려지나" 이고, 그래서 1회차와 직접 비교된다.
  const probes = [{ key: 'list', url: `${BASE_URL}/api/products?size=30` }];

  // 동네 필터 회차(REGION_CODE 지정)는 **필터를 건 무부하**를 따로 잰다.
  // 판정 분모를 필터 무부하로 두는 이유: 그래야 1회차와 같은 잣대(그 경로의 무부하 대비
  // 몇 배)가 되어 **동시성 축의 악화만** 비교된다. 필터의 고정 비용은 아래 두 기준선의
  // 비(比)로 따로 나오므로 섞지 않는다.
  if (REGION_CODE) {
    probes.push({ key: 'filtered', url: `${BASE_URL}/api/products?size=30&regionCodes=${REGION_CODE}` });
  }

  const baseline = measureBaseline(probes);
  const judged = REGION_CODE ? baseline.filtered : baseline.list;

  console.log(
    `이 회차 무부하 기준선: 목록 ${baseline.list.toFixed(1)}ms` +
    (REGION_CODE
      ? ` / 동네필터(${REGION_CODE}) ${baseline.filtered.toFixed(1)}ms ` +
        `→ 필터 고정 비용 ${(baseline.filtered / baseline.list).toFixed(2)}배`
      : '') +
    ` (판정 허용선 ${(judged * ALLOWED_RATIO).toFixed(1)}ms = ${ALLOWED_RATIO}배)`
  );
  return { baseline, judged, tokens };
}

// 반복 1회 = 사람 1명이 상품 목록 화면에 한 번 들어오는 것. 그게 전부다.
// think time 을 넣지 않는다 — 다음 행동이 없고, 도착률 자체가 부하 모델이기 때문이다.
// 여기서 sleep 을 하면 VU 만 더 오래 붙잡아 같은 도착률에 더 많은 VU 가 필요해질 뿐이다.
export default function (data) {
  const opts = REGION_CODE ? { regionCode: REGION_CODE } : {};
  // VU 마다 다른 계정을 쓴다. 계정이 VU 보다 적으면 돌려 쓴다 — 같은 계정의 me/* 응답은
  // 캐시가 잘 들 수 있어 실제보다 낙관적일 수 있는 지점이라, 결과에 계정 수를 함께 남긴다.
  const res = LOGIN
    ? screenProductListLoggedIn(data.tokens[(exec.vu.idInTest - 1) % data.tokens.length], opts)
    : screenProductList(opts);
  recordRatio(res, data.judged);
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

  // 판정 분모. 필터 회차는 **필터를 건 무부하**가 분모다(setup 주석 참고).
  const judged = (data.setup_data && data.setup_data.judged) || base;
  const filtered = data.setup_data && data.setup_data.baseline && data.setup_data.baseline.filtered;

  const pathCost = base - BASELINE_MS.list;
  const pass = ratio['p(95)'] < ALLOWED_RATIO && failed < 0.01;

  // k6 가 목표 도착률을 못 채우고 버린 반복. 0 이 아니면 그 구간은 **앱이 아니라 부하
  // 생성기의 한계**를 잰 것이라 판정에 쓸 수 없다. 맥 한 대에서 돌릴 때 특히 그렇다.
  const dropped = (data.metrics.dropped_iterations && data.metrics.dropped_iterations.values.count) || 0;

  const lines = [
    '',
    '── 1단계 판정 ──────────────────────────────────────',
    `  측정 대상     ${LOGIN ? `로그인 (계정 ${LOGIN_ACCOUNTS}개)` : '비로그인'} · ${REGION_CODE ? `동네 필터 (${REGION_CODE})` : '필터 없음'}`,
    `  무부하 기준   목록 ${base.toFixed(1)} ms (이 회차 setup 에서 실측)`,
    ...(filtered ? [
      `                동네필터 ${filtered.toFixed(1)} ms → **필터 고정 비용 ${(filtered / base).toFixed(2)} 배**`,
    ] : []),
    `  참고          호스트 curl ${BASELINE_MS.list} ms → 경로 비용 ${pathCost >= 0 ? '+' : ''}${pathCost.toFixed(1)} ms`,
    `  허용선        무부하의 ${ALLOWED_RATIO} 배 (= ${(judged * ALLOWED_RATIO).toFixed(1)} ms)`,
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
