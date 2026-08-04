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
