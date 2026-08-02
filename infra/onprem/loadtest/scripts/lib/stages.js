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

/** 사람의 생각하는 시간(초). 화면을 훑고 다음 행동까지. */
export const THINK_MIN = Number(__ENV.THINK_MIN || 3);
export const THINK_MAX = Number(__ENV.THINK_MAX || 5);
export function thinkTime() {
  return THINK_MIN + Math.random() * (THINK_MAX - THINK_MIN);
}
