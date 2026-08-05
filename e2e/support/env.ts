/**
 * 접속 주소의 단일 출처.
 *
 * 기본값을 config 와 fixtures 두 곳에 두었더니 한쪽만 바꿔서 "설정은 18080 인데 요청은 8080 으로
 * 나가는" 상태가 됐다. 주소를 아는 곳은 이 파일 하나뿐이어야 한다.
 *
 * 포트는 dev 환경(8080/3000/3306/6379)과 어긋나 있다 — docker-compose.e2e.yml 참고.
 */

export const BACKEND_URL = process.env.E2E_BACKEND_URL ?? 'http://localhost:18080';
export const FRONTEND_URL = process.env.E2E_FRONTEND_URL ?? 'http://localhost:13000';
export const REDIS_URL = process.env.E2E_REDIS_URL ?? 'redis://localhost:6380';
export const MAILPIT_URL = process.env.E2E_MAILPIT_URL ?? 'http://localhost:8025';

/**
 * e2e 스택 관리자의 비밀번호. 백엔드 `AdminSeeder` 가 이 값으로 계정을 만들고
 * fixtures 가 같은 값으로 로그인한다 — **docker-compose.e2e.yml 의 `APP_SEED_ADMIN_PASSWORD` 와
 * 기본값까지 일치해야 한다.** 한쪽만 바꾸면 관리자 시나리오가 전부 401 로 죽는다.
 *
 * 이 값이 소스에 있어도 되는 이유: e2e 스택은 로컬 전용 일회성 컨테이너이고, `AdminSeeder` 는
 * `@Profile("dev")` 라 운영에는 빈 자체가 없다. 운영 관리자는 배포 후 수동 생성한다.
 * (이전에는 운영에서도 시더가 돌면서 소스의 비밀번호가 그대로 통했다 — 그게 고친 문제다.)
 */
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? 'e2e-admin-pw';
