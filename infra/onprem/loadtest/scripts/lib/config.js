// 공통 설정과 계약. 모든 시나리오가 여기를 거친다.

import http from 'k6/http';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost';

// perf 볼륨 테스트 실측(상품 10만 건, 맥 호스트 curl 기준).
// **판정에는 쓰지 않는다.** 측정 경로가 바뀌면(컨테이너 경유·LAN·유선) 같은 앱이어도 무부하가
// 달라지기 때문이다 — 호스트 16.9ms 대 k6 컨테이너 23.9ms 대 LAN 25.6ms 로 실측됐다.
// 판정 기준선은 setup() 이 그 회차에 직접 재고, 이 값은 결과에 나란히 찍어
// "이 회차의 경로 비용이 얼마인가"를 읽는 참고값으로만 남긴다.
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

// 이 배수를 넘으면 테스트를 중단한다(Breakpoint 회차에서 붕괴 구간을 오래 돌지 않기 위해).
export const ABORT_RATIO = Number(__ENV.ABORT_RATIO || 10);

/**
 * 판정 지표. 절대 ms 가 아니라 **그 회차의 무부하 대비 배수**를 기록한다.
 *
 * 왜 이렇게 하나: k6 는 `options.thresholds` 를 init 컨텍스트에서 읽는다 — setup() 보다
 * 먼저다. 그래서 "잰 무부하 × 3" 을 임계값에 넣는 것은 순서상 불가능하다.
 * 임계값을 계산하는 대신 지표 쪽을 배수로 바꾼다. 임계값은 3 으로 고정이고, 측정 경로가
 * 바뀌어도 무부하가 알아서 따라온다 — 맥 회차와 노트북 회차를 같은 잣대로 비교할 수 있다.
 */
export const latencyRatio = new Trend('latency_ratio');

// 무부하 표본 수. **근거 있는 실측값이 아니라 임의로 정한 값이다.**
// 앞 5 회는 콜드 스타트(JIT·커넥션 풀·버퍼풀 워밍업)라 버리고 남은 15 개의 중앙값을 쓴다.
// 평균이 아니라 중앙값인 이유는 한 번 튄 값에 기준선 전체가 끌려가지 않게 하기 위해서다.
//
// 처음에 3+7 로 두었다가 늘렸다. 같은 조건의 SMOKE 두 번에서 기준선이 19.1ms 와 11.7ms 로
// 잡혀 배수 판정이 1.72배 ↔ 2.37배로 뒤집혔다 — **기준선은 판정의 분모라 여기가 흔들리면
// 기록된 모든 숫자가 흔들린다.** 20 건은 0.5 초도 걸리지 않고, 아래 태그 분리 덕분에
// 보고 숫자에도 섞이지 않는다.
const BASELINE_WARMUP = 5;
const BASELINE_SAMPLES = 15;

/**
 * setup() 에서 부른다. VU 1 로 무부하를 재서 **이 회차의 기준선**을 만든다.
 *
 *   probes: [{ key: 'list', url: `${BASE_URL}/api/products?size=30` }, ...]
 *   반환:   { list: 23.9, ... }  (ms)
 */
export function measureBaseline(probes) {
  const out = {};
  for (const p of probes) {
    const samples = [];
    const params = { tags: { name: `baseline_${p.key}` } };
    // 로그인 회차는 기준선도 **같은 헤더로** 재야 한다. 분모와 분자의 요청 모양이 다르면
    // 배수가 앱 성능이 아니라 요청 모양의 차이를 재게 된다.
    if (p.headers) params.headers = p.headers;
    for (let i = 0; i < BASELINE_WARMUP + BASELINE_SAMPLES; i++) {
      // 쓰기 경로는 POST 로 잰다. 기준선 측정도 행을 만들지만(20건) 10만 건 대비 무시할 수준이고,
      // **분모와 분자가 같은 요청이어야** 배수가 의미를 갖는다.
      const res = p.method === 'POST' ? http.post(p.url, p.body, params) : http.get(p.url, params);
      // 여기에 429 가 섞이면 본문 없는 3ms 가 기준선이 되고, 이후 모든 판정이 무의미해진다.
      expectOk(res, `baseline_${p.key}`, p.okStatuses || [200]);
      if (i >= BASELINE_WARMUP) samples.push(res.timings.duration);
    }
    samples.sort((a, b) => a - b);
    out[p.key] = samples[Math.floor(samples.length / 2)];
  }
  return out;
}

/**
 * 요청 하나를 무부하 대비 배수로 환산해 기록한다.
 *
 * `path` 를 주면 태그가 붙어 **경로별로 나눠 볼 수 있다.** 복합 시나리오에서 특히 중요하다 —
 * 배수는 각 경로를 자기 무부하로 나눈 값이라 **서로 다른 경로를 한 지표에 합쳐도 의미가 있고**
 * (그게 절대 ms 가 아니라 배수로 판정하는 이유다), 동시에 태그로 갈라 어느 경로가 먼저
 * 무너졌는지 짚을 수 있다.
 */
export function recordRatio(res, baselineMs, path) {
  latencyRatio.add(res.timings.duration / baselineMs, path ? { path } : undefined);
}

/**
 * 판정 기준. 시나리오마다 따로 쓰면 회차 비교가 어긋나므로 한 곳에서 만든다.
 * 절대 ms 임계값을 쓰던 예전 `thresholdsFor()` 는 제거했다 — 두 가지 판정 경로가 남아 있으면
 * 어느 쪽으로 재판정됐는지 나중에 알 수 없다.
 *
 * `reportNames` 로 준 태그는 **보고용 서브메트릭**이 된다. `setup()` 의 기준선 측정
 * (`baseline_*`)도 http_req_duration·http_reqs 에 그대로 섞이는데, 그걸 빼고 부하 구간의
 * 해당 요청만 보기 위해서다. k6 는 **임계값이 걸린 태그 조합만** 따로 집계하므로
 * 임계값을 거는 것 외에 서브메트릭을 만들 방법이 없다 — 값 60 초는 사실상 걸리지 않는
 * 크기이고 **판정용이 아니다.** 판정은 위의 latency_ratio 하나뿐이다.
 */
export function ratioThresholds(reportNames = []) {
  const t = {
    http_req_failed: ['rate<0.01'],
    latency_ratio: [
      `p(95)<${ALLOWED_RATIO}`,
      // **무릎을 확실히 넘으면 그 자리에서 끝낸다.** 2회차에서 무릎을 넘긴 뒤 10분을 붕괴
      // 상태로 돌았고, 그 구간은 VM CPU 90% 라 통째로 폐기했다 — 10분이 버려졌을 뿐 아니라
      // 호스트 포화가 무릎 직후 구간까지 오염시켰다.
      // 허용선(3배)이 아니라 그보다 높은 값에서 끊는 이유: Breakpoint 는 넘는 지점을 찾는
      // 것이 목적이라 **확실히 무너진 뒤** 끊어야 무릎이 기록된다.
      // ABORT_RATIO 기본 10 은 임의로 고른 값이다 — 2회차 무릎 구간이 12.4배였던 것을 참고했다.
      { threshold: `p(95)<${ABORT_RATIO}`, abortOnFail: true, delayAbortEval: '30s' },
    ],
  };
  for (const name of reportNames) {
    t[`http_req_duration{name:${name}}`] = ['p(95)<60000'];
  }
  return t;
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
export function expectOk(res, name, okStatuses = [200]) {
  if (res.status === 429) {
    exec.test.abort(
      `${name} 이 429 다. 요청 제한(IP 당 기본 초당 6건)에 걸렸다 — 이 상태로 측정하면 ` +
      `앱이 아니라 제한기를 재게 된다. 맥의 infra/onprem 폴더에서 제한을 올리고 다시 실행할 것:\n` +
      `  RATE_LIMIT_CAPACITY=100000 docker compose --env-file .env up -d app`
    );
  }
  // 등록은 201 을 돌려준다. 시나리오마다 정상 코드가 달라 목록으로 받는다.
  if (okStatuses.indexOf(res.status) === -1) {
    exec.test.abort(`${name} 이 HTTP ${res.status} (기대: ${okStatuses.join('/')}). 측정을 중단한다.`);
  }
}
