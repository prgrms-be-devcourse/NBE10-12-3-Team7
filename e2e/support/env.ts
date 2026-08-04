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
