// VU 계단. **시나리오끼리 같은 계단을 써야 비교가 성립한다.**
//
// 계단을 각 시나리오에 흩어 적으면 하나만 고쳐도 조건이 어긋나고, 나중에 "이 회차는 계단이
// 달랐다"를 알아채지 못한다. 한 곳에 모아 이름으로 부른다.
//
// VU 1명 = 사람 1명이다(screens.js 참고). 요청 수가 아니다.

/**
 * 기본 계단. 5 → 400 까지 올리며 무릎을 찾는다.
 *
 * 400 까지 잡은 근거: 이 앱의 이론상 한계가 동시 770명이다
 * (DB 커넥션 10개 ÷ 목록 응답 13ms → 초당 770건, think time 4초 기준).
 * 경험적으로 이론값의 30~50% 에서 꺾이므로 무릎은 200~400 사이일 것으로 본다.
 * 각 단계 2분 유지 + 30초 램프업 → 총 약 15분.
 *
 * 램프업 구간의 값은 과도기라 판정에 쓰지 않는다. 유지 구간만 본다.
 */
export const LADDER_400 = [
  { duration: '30s', target: 5 },
  { duration: '2m', target: 5 },
  { duration: '30s', target: 20 },
  { duration: '2m', target: 20 },
  { duration: '30s', target: 50 },
  { duration: '2m', target: 50 },
  { duration: '30s', target: 100 },
  { duration: '2m', target: 100 },
  { duration: '30s', target: 200 },
  { duration: '2m', target: 200 },
  { duration: '30s', target: 400 },
  { duration: '2m', target: 400 },
  { duration: '30s', target: 0 },
];

/**
 * 짧은 확인용. 스크립트가 도는지 볼 때만 쓰고 기록에는 남기지 않는다.
 */
export const SMOKE = [
  { duration: '10s', target: 3 },
  { duration: '20s', target: 3 },
  { duration: '5s', target: 0 },
];

/**
 * 유지 테스트. 무릎에서 찾은 VU 로 10분을 버티는지 본다.
 *   SOAK_VU=200 docker compose run --rm k6 run /scripts/s01-arrival.js
 *
 * 30초는 버티는데 5분 뒤 무너지는 경우가 흔하다(커넥션 누수·GC 누적).
 * 짧게 재고 "괜찮다"고 하면 안 된다.
 */
export function soak(vus) {
  return [
    { duration: '1m', target: vus },
    { duration: '10m', target: vus },
    { duration: '30s', target: 0 },
  ];
}

/**
 * 환경변수로 계단을 고른다. 시나리오마다 같은 방식으로 부른다.
 *   (기본)          LADDER_400
 *   SMOKE=true      SMOKE
 *   SOAK_VU=200     soak(200)
 */
export function pickStages() {
  if (__ENV.SMOKE === 'true') return SMOKE;
  if (__ENV.SOAK_VU) return soak(Number(__ENV.SOAK_VU));
  return LADDER_400;
}

// ── 도착률 계단 ────────────────────────────────────────────────────────────
//
// **"초당 몇 명이 들어오는 것까지 버티나"를 잰다.** 위의 VU 계단과 답하는 질문이 다르다.
//
// VU 계단은 결론이 think time 가정에 통째로 의존한다 — "VU 400 = 초당 100명"이라는 환산이
// THINK_MIN·MAX(우리가 정한 3~5초)에서 나오기 때문이다. 도착률로 재면 그 가정이 사라지고,
// VU 환산은 나중에 원하는 think time 으로 계산해 덧붙일 수 있다. 되돌릴 수 있는 방향이다.
//
// **단위는 "초당 화면 진입"이다.** s01 의 화면 하나가 API 2건(categories + products)을
// 동시에 부르므로, 초당 100 진입이면 초당 200 요청이 나간다.
//
// 600 까지 잡은 근거: 무부하 목록 12ms × 커넥션 풀 10개 → 이론상 초당 833 요청.
// 경험적으로 이론값의 30~50% 에서 꺾이므로 무릎은 초당 125~200 진입 근처로 본다.
// 그 위 단계(400·600)는 상한이 그보다 높을 때만 의미가 있다.
//
// ⚠️ 맥 한 대에서 돌리면 k6 와 앱이 같은 CPU 를 나눠 쓴다. Grafana 의 VM CPU 가 90% 를
// 넘은 구간은 앱이 아니라 호스트 포화를 잰 것이므로 폐기한다.
export const ARRIVAL_LADDER = [
  { duration: '30s', target: 20 },
  { duration: '2m', target: 20 },
  { duration: '30s', target: 50 },
  { duration: '2m', target: 50 },
  { duration: '30s', target: 100 },
  { duration: '2m', target: 100 },
  { duration: '30s', target: 200 },
  { duration: '2m', target: 200 },
  { duration: '30s', target: 400 },
  { duration: '2m', target: 400 },
  { duration: '30s', target: 600 },
  { duration: '2m', target: 600 },
  { duration: '30s', target: 0 },
];

// ── 쓰기 계단 ──────────────────────────────────────────────────────────────
//
// 도착률은 읽기와 같은 값(20~600)이지만 **유지 시간이 1분이다**(읽기는 2분).
//
// 쓰기는 매 요청이 행을 만든다. 읽기 회차는 15분 내내 상품 10만 건으로 고정이었지만,
// 쓰기를 2분씩 유지하면 회차 한 번에 20만 건 넘게 쌓여 **10만이 30만이 된다** — 후반 계단은
// 앞 계단과 다른 데이터 크기에서 재게 되고, 동시성 효과와 데이터량 효과가 섞여 무릎을
// 해석할 수 없다(데이터량 축은 perf 가 따로 잰다).
// 유지를 절반으로 줄이면 생성량도 절반이 된다. 무릎을 넘으면 자동 중단되므로 실제로는 더 적다.
//
// **쓰기 회차끼리만 비교하면 되므로 읽기 회차와 계단이 달라도 문제되지 않는다.**
// 대신 회차 조건에 시작·종료 시점의 상품 수를 남긴다.
export const WRITE_LADDER = [
  { duration: '30s', target: 20 },
  { duration: '1m', target: 20 },
  { duration: '30s', target: 50 },
  { duration: '1m', target: 50 },
  { duration: '30s', target: 100 },
  { duration: '1m', target: 100 },
  { duration: '30s', target: 200 },
  { duration: '1m', target: 200 },
  { duration: '30s', target: 400 },
  { duration: '1m', target: 400 },
  { duration: '30s', target: 600 },
  { duration: '1m', target: 600 },
  { duration: '30s', target: 0 },
];

// 쓰기 상한 탐색 계단. `CEILING=true` 로 고른다.
//
// 쓰기 1회차에서 초당 600 까지 무릎이 없었으므로(pending 0, 락 대기 0, VM CPU 39%)
// 낮은 계단은 건너뛰고 400 부터 올린다. **버티는지만 보면 되므로 유지는 30초**다.
//
// 데이터 누적을 크게 걱정했는데 1회차 실측으로 제약이 풀렸다 — 상품이 10만에서 22만으로
// 두 배가 되는 동안 등록이 오히려 빨라졌다(14ms → 6.2ms). INSERT 는 테이블 크기에 거의
// 둔감하다(인덱스 트리 깊이가 로그로 늘 뿐이다). 그래서 쓰기 회차는 계단을 더 올려도 된다.
//
// ⚠️ **1400 에서 멈추는 이유**: 초당 600 에서 VM CPU 가 39% 였고 외삽하면 1400 근처가 90%
// (우리가 정한 폐기 기준)다. 이 CPU 에는 k6 몫이 섞여 있어 그 위로는 앱이 아니라 **호스트가
// 먼저 막힌다.** 거기까지 문제가 없으면 "맥 한 대에서는 쓰기 상한을 못 찾는다"로 결론짓고
// 다음 단계로 넘어간다 — 앱의 진짜 상한은 부하 생성기를 노트북으로 옮겨야 보인다.
export const WRITE_CEILING = [
  { duration: '30s', target: 400 },
  { duration: '30s', target: 400 },
  { duration: '30s', target: 600 },
  { duration: '30s', target: 600 },
  { duration: '30s', target: 800 },
  { duration: '30s', target: 800 },
  { duration: '30s', target: 1000 },
  { duration: '30s', target: 1000 },
  { duration: '30s', target: 1200 },
  { duration: '30s', target: 1200 },
  { duration: '30s', target: 1400 },
  { duration: '30s', target: 1400 },
  { duration: '30s', target: 0 },
];

/** 쓰기 계단 선택. 읽기의 pickArrivalStages() 와 같은 규칙이다. */
export function pickWriteStages() {
  if (__ENV.SMOKE === 'true') return ARRIVAL_SMOKE;
  if (__ENV.SOAK_RATE) return arrivalSoak(Number(__ENV.SOAK_RATE));
  if (__ENV.CEILING === 'true') return WRITE_CEILING;
  return WRITE_LADDER;
}

/** 짧은 확인용. 스크립트가 도는지 볼 때만 쓰고 기록에는 남기지 않는다. */
export const ARRIVAL_SMOKE = [
  { duration: '10s', target: 5 },
  { duration: '20s', target: 5 },
  { duration: '5s', target: 0 },
];

/** 유지 테스트. 무릎에서 찾은 도착률로 10분을 버티는지 본다. */
export function arrivalSoak(rate) {
  return [
    { duration: '1m', target: rate },
    { duration: '10m', target: rate },
    { duration: '30s', target: 0 },
  ];
}

/**
 * 환경변수로 도착률 계단을 고른다. VU 계단의 pickStages() 와 같은 규칙이다.
 *   (기본)          ARRIVAL_LADDER
 *   SMOKE=true      ARRIVAL_SMOKE
 *   SOAK_RATE=200   arrivalSoak(200)
 */
export function pickArrivalStages() {
  if (__ENV.SMOKE === 'true') return ARRIVAL_SMOKE;
  if (__ENV.SOAK_RATE) return arrivalSoak(Number(__ENV.SOAK_RATE));
  return ARRIVAL_LADDER;
}

/**
 * 사람의 생각하는 시간(초). 화면을 훑고 다음 행동까지.
 *
 * **도착률 계단에서는 쓰지 않는다.** s01 은 "화면을 한 번 연다"가 전부라 다음 행동이 없고,
 * 도착률이 부하 모델 자체이기 때문이다. VU 계단을 쓰는 시나리오에서만 필요하다.
 */
export const THINK_MIN = Number(__ENV.THINK_MIN || 3);
export const THINK_MAX = Number(__ENV.THINK_MAX || 5);
export function thinkTime() {
  return THINK_MIN + Math.random() * (THINK_MAX - THINK_MIN);
}
