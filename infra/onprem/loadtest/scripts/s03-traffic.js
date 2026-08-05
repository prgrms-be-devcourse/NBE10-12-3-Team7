// 복합 트래픽 — **사용자가 몰렸을 때 이 서비스가 어디까지 버티는가.**
//
// 지금까지는 경로를 하나씩 격리해서 쟀다. 그래서 무릎이 나올 때마다 원인을 짚을 수 있었다.
// **이 시나리오는 반대다** — 실제 사용자 구성에 가깝게 섞어서 **총 상한**을 잰다.
//
// 지금 이걸 해도 되는 이유: 격리 측정을 먼저 끝냈기 때문이다. 각 경로의 단독 상한을 알고
// 있으므로, 섞었을 때 나온 숫자를 분해할 수 있다. 순서가 반대였다면 "초당 N 까지 버팁니다"
// 라는 숫자 하나만 남고 무엇이 발목을 잡았는지 몰랐을 것이다.
//
//   격리 회차 결과 (results/ 참고)
//   ├─ 목록(필터 없음)  초당 600 까지 무릎 없음
//   ├─ 목록(동네 필터)  무릎 초당 200~230  ← 커넥션 풀 고갈
//   └─ 등록(쓰기)       무릎 초당 1,000~1,200 ← 커넥션 풀 고갈
//
// 실행(맥):
//   ./scenarios/run.sh s03-traffic baseline
//   SMOKE=true ./scenarios/run.sh s03-traffic wiring
//   POLL=true ./scenarios/run.sh s03-traffic with-polling     # 알림 폴링 배경부하 추가

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
import {
  screenProductList,
  screenProductListLoggedIn,
  screenProductDetail,
  pickFromList,
} from './lib/screens.js';
import { ARRIVAL_LADDER, ARRIVAL_SMOKE } from './lib/stages.js';
import http from 'k6/http';

// ── 트래픽 구성 ────────────────────────────────────────────────────────────
//
// **이 비율은 실측이 아니라 우리가 정한 모델이다.** 실사용자가 없어 트래픽 로그가 없기 때문이다.
// 근거 없는 숫자를 근거 있는 척하지 않기 위해, 어떻게 유도했는지 적어둔다.
//
// 한 사람의 세션을 이렇게 가정했다:
//   목록 진입 1회 + 스크롤 2회(목록 API 재호출) + 상세 진입 2회  →  목록 계열 3 : 상세 2
//   = 목록 60% / 상세 40%
//
// 유도의 근거는 **화면 구조상의 제약**이다 — 목록을 거치지 않으면 상세에 갈 수 없으므로
// 목록이 상세보다 적을 수 없고, 스크롤은 같은 목록 API 를 다시 부른다.
// "한 세션에 상세 2개"는 우리가 고른 값이다.
//
// 비로그인 : 로그인 비율은 **근거가 전혀 없다.** 1:1 로 두고 env 로 조정할 수 있게 했다.
//
// 로그인 사용자는 동네 필터가 붙는다 — 프론트가 활성 동네가 있으면 regionCodes 를 붙이기
// 때문이다(products/page.tsx). 이 경로가 격리 측정에서 무릎이 가장 낮았으므로
// **복합 상한을 좌우할 가능성이 높다.**
const W_GUEST = Number(__ENV.W_GUEST || 30); // 비로그인 목록 %
const W_MEMBER = Number(__ENV.W_MEMBER || 30); // 로그인 목록(동네 필터) %
const W_DETAIL = Number(__ENV.W_DETAIL || 40); // 상세 %

const REGION_CODE = __ENV.REGION_CODE || '1111010100';
const LOGIN_ACCOUNTS = Number(__ENV.LOGIN_ACCOUNTS || 300);
const POLL = __ENV.POLL === 'true';

const LADDER = __ENV.SMOKE === 'true' ? ARRIVAL_SMOKE : ARRIVAL_LADDER;

/**
 * 계단을 비율만큼 줄인다. 총 도착률이 계단이고 각 경로가 그중 정해진 몫을 차지한다.
 * 이렇게 해야 **구성비를 고정한 채 총량만 올릴 수 있다** — 그게 "트래픽이 몰린다"의 뜻이다.
 */
function share(pct) {
  return LADDER.map((s) => ({ duration: s.duration, target: Math.max(1, Math.round((s.target * pct) / 100)) }));
}

function arrivalScenario(execName, pct, startTime) {
  return {
    executor: 'ramping-arrival-rate',
    exec: execName,
    startRate: 0,
    timeUnit: '1s',
    stages: share(pct),
    preAllocatedVUs: Number(__ENV.PRE_VUS || 30),
    maxVUs: Number(__ENV.MAX_VUS || 400),
    ...(startTime ? { startTime } : {}),
  };
}

const scenarios = {
  list_guest: arrivalScenario('listGuest', W_GUEST),
  list_member: arrivalScenario('listMember', W_MEMBER),
  detail: arrivalScenario('viewDetail', W_DETAIL),
};

// 알림 폴링은 **배경 부하**다. 계단과 무관하게 일정한 속도로 깔린다 —
// 화면을 열어둔 사용자가 주기적으로 배지를 갱신하는 것을 모델링한다.
// 도착률 계단에 섞으면 "몰리는 트래픽"과 "상시 트래픽"이 구분되지 않는다.
if (POLL) {
  scenarios.notify_poll = {
    executor: 'constant-arrival-rate',
    exec: 'pollNotifications',
    rate: Number(__ENV.POLL_RATE || 50),
    timeUnit: '1s',
    duration: __ENV.POLL_DURATION || '15m30s',
    preAllocatedVUs: 20,
    maxVUs: 100,
  };
}

export const options = {
  // 본문을 쓰지 않는다(expectOk 는 상태 코드만, 판정은 res.timings 만 본다).
  // 단 setup 에서 목록 응답의 productId 를 뽑아야 하므로 그 요청만 되살린다.
  discardResponseBodies: true,
  scenarios,
  summaryTrendStats: TREND_STATS,
  thresholds: Object.assign(
    ratioThresholds(['products_list', 'product_detail']),
    // 경로별 배수를 **요약에서도** 보려고 서브메트릭을 만든다. k6 는 임계값이 걸린 태그
    // 조합만 따로 집계하므로 이 방법뿐이다 — 값 60 은 사실상 걸리지 않는 크기이고
    // **판정용이 아니다.** 판정은 위 latency_ratio 통합값 하나다.
    // 어느 경로가 먼저 무너졌는지가 이 시나리오의 핵심이라 이게 없으면 결과를 못 읽는다.
    {
      'latency_ratio{path:guest}': ['p(95)<60'],
      'latency_ratio{path:member}': ['p(95)<60'],
      'latency_ratio{path:detail}': ['p(95)<60'],
    }
  ),
  tags: { scenario: 's03-traffic' },
};

export function setup() {
  const tokens = issueTokens(LOGIN_ACCOUNTS);

  // 상세로 들어갈 상품 id 를 목록에서 뽑는다. 하드코딩하지 않는 이유는 데이터를 다시 적재하면
  // id 가 바뀌기 때문이다(perf 에서 id 를 박아뒀다가 404 를 맞은 적이 있다).
  const seed = http.get(`${BASE_URL}/api/products?size=100`, {
    tags: { name: 'setup_pick' },
    responseType: 'text', // discardResponseBodies 예외 — 본문에서 id 를 꺼내야 한다
  });
  const productIds = pickFromList(seed).productIds;
  if (!productIds.length) exec.test.abort('상품 목록이 비어 있다 — 데이터를 먼저 적재할 것.');

  // 경로마다 무부하가 다르다. **각 경로를 자기 무부하로 나눠야** 배수가 의미를 갖는다.
  const baseline = measureBaseline([
    { key: 'guest', url: `${BASE_URL}/api/products?size=30` },
    { key: 'member', url: `${BASE_URL}/api/products?size=30&regionCodes=${REGION_CODE}` },
    { key: 'detail', url: `${BASE_URL}/api/products/${productIds[0]}` },
  ]);

  console.log(
    `무부하 기준선 — 비로그인 목록 ${baseline.guest.toFixed(1)}ms · ` +
    `로그인 목록(동네) ${baseline.member.toFixed(1)}ms · 상세 ${baseline.detail.toFixed(1)}ms\n` +
    `구성비 비로그인 ${W_GUEST}% / 로그인 ${W_MEMBER}% / 상세 ${W_DETAIL}%` +
    (POLL ? ` · 알림 폴링 배경 초당 ${__ENV.POLL_RATE || 50}` : '')
  );
  return { baseline, tokens, productIds };
}

// ── 경로별 동작 ────────────────────────────────────────────────────────────

export function listGuest(data) {
  const res = screenProductList();
  recordRatio(res, data.baseline.guest, 'guest');
}

export function listMember(data) {
  const res = screenProductListLoggedIn(tokenFor(data.tokens), { regionCode: REGION_CODE });
  recordRatio(res, data.baseline.member, 'member');
}

export function viewDetail(data) {
  const ids = data.productIds;
  const id = ids[exec.scenario.iterationInTest % ids.length];
  const res = screenProductDetail(id);
  recordRatio(res, data.baseline.detail, 'detail');
}

export function pollNotifications(data) {
  const token = tokenFor(data.tokens);
  http.get(`${BASE_URL}/api/notifications/unread-count`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'notify_poll' },
  });
  // 배경 부하는 **판정에 넣지 않는다.** 상한을 재는 대상이 아니라 조건이기 때문이다.
}

export function handleSummary(data) {
  const b = data.setup_data && data.setup_data.baseline;
  const ratio = data.metrics.latency_ratio && data.metrics.latency_ratio.values;
  const failed = data.metrics.http_req_failed.values.rate;
  const iters = data.metrics.iterations.values;

  if (!b || !ratio) {
    return {
      stdout: '\n⚠️ 기준선 또는 배수 표본이 없다 — 판정을 건너뛴다(setup 이 중단됐을 수 있다).\n',
      '/results/s03-traffic-summary.json': JSON.stringify(data, null, 2),
    };
  }

  const dropped = (data.metrics.dropped_iterations && data.metrics.dropped_iterations.values.count) || 0;
  const pass = ratio['p(95)'] < ALLOWED_RATIO && failed < 0.01;
  const sub = (n) => {
    const m = data.metrics[`http_req_duration{name:${n}}`];
    return m ? `${m.values['p(95)'].toFixed(1)} ms` : '표본 없음';
  };
  const byPath = (p) => {
    const m = data.metrics[`latency_ratio{path:${p}}`];
    return m ? `${m.values['p(95)'].toFixed(2)} 배` : '표본 없음';
  };

  const lines = [
    '',
    '── 복합 트래픽 판정 ────────────────────────────────',
    `  구성비        비로그인 목록 ${W_GUEST}% · 로그인 목록(동네 ${REGION_CODE}) ${W_MEMBER}% · 상세 ${W_DETAIL}%`,
    `                ${POLL ? `+ 알림 폴링 배경 초당 ${__ENV.POLL_RATE || 50}` : '배경 부하 없음'}`,
    '',
    `  무부하 기준   비로그인 ${b.guest.toFixed(1)} ms · 로그인 ${b.member.toFixed(1)} ms · 상세 ${b.detail.toFixed(1)} ms`,
    `  허용선        각 경로의 무부하 대비 ${ALLOWED_RATIO} 배`,
    '',
    `  배수 p95      ${ratio['p(95)'].toFixed(2)} 배   ← 판정. 세 경로를 합친 값`,
    `  배수 p99      ${ratio['p(99)'].toFixed(2)} 배`,
    '',
    '  경로별 배수 p95  ← **어느 경로가 먼저 무너졌나**',
    `    비로그인 목록   ${byPath('guest')}`,
    `    로그인 목록(동네) ${byPath('member')}`,
    `    상세            ${byPath('detail')}`,
    `  목록 응답 p95 ${sub('products_list')}`,
    `  상세 응답 p95 ${sub('product_detail')}`,
    `  실패율        ${(failed * 100).toFixed(2)} %`,
    `  소화한 진입   ${iters.rate.toFixed(1)} 건/초 (총 ${iters.count})`,
    `  버린 반복     ${dropped}${dropped > 0
      ? '  ⚠️ 목표 도착률을 못 채웠다 — 부하 생성기 한계일 수 있다'
      : '  (목표 도착률을 전부 채웠다)'}`,
    '',
    `  판정          ${pass ? '허용선 안' : '⚠️ 허용선을 넘었다'}`,
    '',
    '  ※ 배수는 **각 경로를 자기 무부하로 나눈 값**이라 경로가 달라도 합칠 수 있다.',
    '     어느 경로가 먼저 무너졌는지는 Grafana 에서 `path` 태그로 갈라 본다.',
    '  ※ 구성비는 실측이 아니라 우리가 정한 모델이다(스크립트 상단 주석 참고).',
    '     비율을 바꾸면 상한도 바뀌므로 회차마다 구성비를 함께 기록한다.',
    '',
  ].join('\n');

  return {
    stdout: lines,
    '/results/s03-traffic-summary.json': JSON.stringify(data, null, 2),
  };
}
