// # k6 부하테스트 — 회원 내 정보 조회 (`GET /api/members/me`)
//
// > 담당: member / auth 도메인
// > 실행 위치: **팀원 테스트 서버 직접 호출**
// > 인증 방식: **단일 JWT Access Token을 모든 VU가 공용으로 사용**
//
// ---
//
// ## 대상 API
//
// | 항목 | 값 |
// |---|---|
// | 엔드포인트 | `GET /api/members/me` |
// | 인증 | `Authorization: Bearer <JWT Access Token>` |
// | Request Body | 없음 |
// | 테스트 방식 | 단일 JWT로 동시 요청 증가 |
// | 특징 | JWT 인증 필터 처리 + 회원 단건 조회 + DTO 변환 + JSON 직렬화 |
//
// ---
//
// ## 테스트 목적
//
// 이번 테스트는 이미 로그인한 회원이 자신의 정보를 조회하는 상황에서 서버가 동시 요청을 안정적으로 처리할 수 있는지 확인하는 것을 목적으로 한다.
//
//     `GET /api/members/me`는 로그인 직후, 마이페이지 진입, 웹 새로고침, 앱 재실행 등에서 반복적으로 호출될 가능성이 높은 API이다.
//
//     해당 API 요청에는 다음 처리 과정이 포함된다.
//
// 1. `Authorization` 헤더에서 Bearer Token 추출
// 2. JWT 서명 및 만료 여부 검증
// 3. JWT에서 회원 식별자 추출
// 4. Spring Security 인증 객체 구성
// 5. 회원 데이터 조회
// 6. 응답 DTO 변환
// 7. JSON 직렬화 및 응답
//
// 따라서 이번 테스트에서는 단순한 DB 조회 시간뿐 아니라 JWT 인증 필터를 포함한 전체 요청 처리 시간을 함께 측정한다.
//
// ---
//
// ## 단일 JWT를 사용하는 이유
//
// 이번 1차 테스트는 여러 계정의 실제 사용자 트래픽을 완전히 재현하는 테스트가 아니라, 인증된 회원 정보 조회 API의 기본 성능을 측정하는 **Baseline 테스트**로 진행한다.
//
//     모든 VU는 동일한 JWT Access Token을 사용한다.
//
//     ```text
// VU 1  ─┐
// VU 2  ─┤
// VU 3  ─┼─ 동일한 JWT Access Token 사용
// ...    │
// VU 50 ─┘
// ```
//
// 따라서 이 테스트의 의미는 다음과 같다.
//
// > 동일한 인증 회원에 대해 동시 요청이 증가할 때 JWT 검증과 회원 조회 API가 어느 수준까지 안정적으로 처리되는지 확인한다.
//
//     주의할 점은 동시 VU 50명이 서로 다른 회원 50명을 의미하지 않는다는 것이다.
//
//     ```text
// 동시 VU 50명
// ≠ 서로 다른 회원 50명
//
// 동시 VU 50명
// = 동일한 회원 JWT를 사용하는 50개의 동시 실행 흐름
// ```
//
// 실제 다중 사용자 환경을 재현하려면 추후 테스트 계정을 여러 개 준비하고 계정별 JWT Pool을 구성해야 한다.
//
// ---
//
// ## 테스트 시나리오
//
//     `ramping-vus` 방식으로 동시 사용자 수를 단계적으로 증가시킨다.
//
// | 단계 | 시간 | 목표 VU | 목적 |
// |---|---:|---:|---|
// | 1 | 30초 | 10명 | 초기 안정성 확인 |
// | 2 | 1분 | 30명 | 일반 부하 구간 확인 |
// | 3 | 1분 | 50명 | 응답 지연 및 오류 증가 여부 확인 |
// | 4 | 30초 | 0명 | 부하 종료 후 정상 회복 확인 |
//
// `target`은 초당 요청 수가 아니라 동시에 실행되는 Virtual User 수이다.
//
//     각 VU는 요청 완료 후 1초간 대기하므로 실제 초당 요청 수는 서버 응답시간에 따라 달라진다.
//
// ---
//
// ## 합격 기준
//
// | 항목 | 기준 |
// |---|---:|
// | `member_me_get_latency` p95 | 500ms 미만 |
// | `member_me_get_errors` | 1% 미만 |
// | `http_req_failed` | 1% 미만 |
// | HTTP 상태 코드 | `200 OK` |
// | 응답 본문 | 비어 있지 않을 것 |
//
// 위 기준은 최종 SLA가 아니라 첫 번째 테스트의 초기 기준이다.
//
//     첫 실행 결과를 Baseline으로 저장하고, 이후 서버 사양과 팀 성능 기준에 따라 조정한다.
//
// ---
//
// ## 스크립트 `member_me_get.js`


import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ══════════════════════════════════════════════════════════════
// ✏️ 이 블록만 각자 채우세요 (아래 default function은 건드리지 않음)
// ══════════════════════════════════════════════════════════════

/*
 * 1. 테스트 이름
 *
 * 결과 지표와 리포트에서 테스트를 구분하는 이름이다.
 * API 기능과 HTTP Method를 함께 표시해 다른 테스트와 구분하기 쉽게 작성한다.
 */
const TEST_NAME = 'member_me_get';

/*
 * 2. 테스트 대상 엔드포인트
 *
 * 로그인한 사용자가 자신의 회원 정보를 조회하는 API이다.
 * 실제 요청 주소는 BASE_URL + ENDPOINT 형태로 만들어진다.
 *
 * 예시:
 * BASE_URL = http://localhost:8080
 * 최종 주소 = http://localhost:8080/api/members/me
 */
const ENDPOINT = '/api/members/me';

/*
 * 3. HTTP Method
 *
 * 내 정보 조회는 데이터를 변경하지 않는 조회 API이므로 GET을 사용한다.
 */
const METHOD = 'GET';

/*
 * 4. 요청 본문 생성 함수
 *
 * GET 요청은 Request Body를 사용하지 않기 때문에 항상 null을 반환한다.
 */
function buildBody() {
  return null;
}

/*
 * 5. 동시 사용자 수 단계
 *
 * ramping-vus 방식이므로 target은 초당 요청 수가 아니라
 * 동시에 요청을 실행하는 Virtual User 수를 의미한다.
 *
 * 모든 VU는 환경변수 TOKEN으로 전달한 동일한 JWT를 사용한다.
 */
const STAGES = [
  { duration: '30s', target: 10 },
  { duration: '1m', target: 30 },
  { duration: '1m', target: 50 },
  { duration: '30s', target: 0 },
];

/*
 * 6. 합격 기준
 *
 * p95 응답시간:
 * 전체 요청의 95%가 500ms 이내에 처리되어야 한다.
 *
 * 에러율:
 * 전체 요청 중 실패 요청 비율이 1% 미만이어야 한다.
 */
const THRESHOLD_P95_MS = 500;
const THRESHOLD_P99_MS = 800;
const THRESHOLD_ERR = 0.01;

/*
 * 7. 요청 타임아웃
 *
 * 일반적인 회원 단건 조회 API이므로 30초로 설정한다.
 */
const REQ_TIMEOUT = '30s';

// ══════════════════════════════════════════════════════════════
// 아래부터는 5명 공통 — 수정 금지 (결과 비교 위해 동일 유지)
// ══════════════════════════════════════════════════════════════

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';

const latency = new Trend(`${TEST_NAME}_latency`, true);
const errors = new Rate(`${TEST_NAME}_errors`);

export const options = {
  scenarios: {
    [TEST_NAME]: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: STAGES,
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    [`${TEST_NAME}_errors`]: [`rate<${THRESHOLD_ERR}`],
    [`${TEST_NAME}_latency`]: [`p(95)<${THRESHOLD_P95_MS}`, `p(99)<${THRESHOLD_P99_MS}`],
    http_req_failed: [`rate<${THRESHOLD_ERR}`],
  },
  // 요약에 p90/p95/p99 를 항상 동일 포맷으로 출력 (개선 전후 비교 기준선)
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const url = `${BASE_URL}${ENDPOINT}`;
  const body = buildBody();

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${TOKEN}`,
    },
    timeout: REQ_TIMEOUT,
    tags: { name: TEST_NAME },   // name 라벨 = API 식별자
  };

  // METHOD에 따라 GET/POST 분기
  const res = METHOD === 'GET'
    ? http.get(url, params)
    : http.post(url, body, params);

  latency.add(res.timings.duration);
  errors.add(res.status !== 200);

  check(res, {
    'status 200': (r) => r.status === 200,
    'body 비어있지 않음': (r) => r.body && r.body.length > 0,
  });

  sleep(1);
}
// ```
//
// ---
//
// ## 실행 방법
//
// ### Windows PowerShell
//
//     ```powershell
// k6 run `
// -e BASE_URL="http://팀원서버주소:8080" `
//   -e TOKEN="JWT_ACCESS_TOKEN" `
//     .\member_me_get.js
//     ```
//
// ### Linux 또는 Git Bash
//
// ```bash
// k6 run \
//   -e BASE_URL="http://팀원서버주소:8080" \
//   -e TOKEN="JWT_ACCESS_TOKEN" \
// ./member_me_get.js
//     ```
//
// `TOKEN` 환경변수에는 `Bearer`를 붙이지 않는다.
//
// ```text
// 올바른 값:
//     TOKEN=eyJhbGciOiJIUzI1NiJ9...
//
// 잘못된 값:
//     TOKEN=Bearer eyJhbGciOiJIUzI1NiJ9...
// ```
//
// 스크립트에서 자동으로 `Bearer ` 접두사를 추가한다.
//
// ---
//
// ## 테스트 전 확인 사항
//
// - 운영 서버가 아닌 테스트 서버인지 확인
// - 테스트 서버의 정확한 Base URL 확인
// - `/api/members/me`가 `200 OK`를 반환하는지 사전 확인
// - 테스트 계정이 정지 또는 탈퇴 상태가 아닌지 확인
// - 테스트 종료 시점까지 만료되지 않는 Access Token 사용
// - Refresh Token이 아닌 Access Token 사용
// - 팀원에게 테스트 시작 시간과 최대 VU 50명을 사전 공유
// - 테스트 중 서버 CPU, 메모리, JVM, HikariCP, DB 상태 확인
//
// ---
//
// ## 테스트 중 확인할 서버 지표
//
// ### 애플리케이션
//
// - CPU 사용률
// - 메모리 사용률
// - JVM Heap 사용량
// - Minor GC / Full GC 발생 여부
// - Tomcat 활성 스레드
// - HTTP 5xx 발생량
//
// ### HikariCP
//
// - Active Connection
// - Idle Connection
// - Pending Connection
// - Connection Timeout
// - Maximum Pool Size 도달 여부
//
// ### 데이터베이스
//
// - 현재 DB Connection 수
// - 회원 조회 쿼리 실행시간
// - Slow Query 발생 여부
// - CPU 및 Disk I/O
// - Lock Wait 발생 여부
//
// ---
//
// ## 결과 확인 항목
//
// k6 결과에서 다음 항목을 확인한다.
//
// | 지표 | 의미 |
// |---|---|
// | `member_me_get_latency` | 대상 API의 응답시간 |
// | `member_me_get_latency p(95)` | 전체 요청 중 95%가 완료된 시간 |
// | `member_me_get_errors` | 상태 코드가 200이 아닌 요청 비율 |
// | `http_req_failed` | k6가 판단한 HTTP 요청 실패율 |
// | `http_reqs` | 전체 요청 수와 초당 요청 수 |
// | `checks` | 상태 코드 및 응답 본문 검증 성공률 |
// | `vus` | 현재 실행 중인 VU 수 |
// | `vus_max` | 최대 VU 수 |
//
// ---
//
// ## 결과 해석
//
// ### 정상적인 결과
//
// - VU가 증가해도 p95 응답시간이 크게 증가하지 않음
// - 에러율이 1% 미만으로 유지됨
// - HTTP 5xx가 지속적으로 발생하지 않음
// - HikariCP Pending Connection이 쌓이지 않음
// - 부하 종료 후 CPU와 메모리가 정상 수준으로 회복됨
//
// ### 병목 가능성이 있는 결과
//
// - 특정 VU 구간부터 p95가 급격히 증가
// - `500`, `502`, `503`, `504` 응답 증가
// - HikariCP Pending Connection 지속 증가
// - DB Connection Pool 최대치 도달
// - Full GC 반복 발생
// - 부하 종료 후에도 자원 사용량이 회복되지 않음
//
// ---
//
// ## 주의사항
//
// - **JWT 검증 포함**: 모든 요청에 Bearer Token이 포함되므로 JWT 인증 필터 처리 시간이 측정에 포함된다.
// - **로그인 비용 제외**: 테스트 중 로그인하지 않으므로 BCrypt 검증과 JWT 발급 비용은 포함되지 않는다.
// - **단일 회원 조회**: 모든 VU가 같은 JWT를 사용하므로 동일한 회원 데이터를 반복 조회한다.
// - **토큰 만료 주의**: 테스트 도중 Access Token이 만료되면 이후 요청이 `401 Unauthorized`로 실패한다.
// - **실제 사용자 수와 구분**: VU 50명은 서로 다른 계정 50명이 아니라 동일 JWT를 사용하는 동시 실행 흐름 50개다.
// - **보안 주의**: 실제 JWT 값은 문서, Git 저장소, 팀 채팅 로그에 그대로 커밋하지 않는다.