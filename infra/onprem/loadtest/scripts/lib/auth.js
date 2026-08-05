// 로그인 토큰 발급. 여러 시나리오가 공유한다.
//
// **회원가입은 부하 경로에 넣지 않는다.** 이메일 인증이 선행 필수라 k6 로는 불가능하고
// (인증 코드가 메일로만 간다), 사용자당 평생 1회라 부하 대상도 아니다.
// `dataset/10-seed.sql` 이 만들어 둔 계정 300개를 쓴다.
//
// **로그인 처리 자체도 부하 구간에 넣지 않는다.** setup() 에서 미리 받아 VU 에 나눠 준다 —
// 재려는 것은 로그인한 사용자의 동작 비용이지 로그인 비용이 아니다.

import http from 'k6/http';
import exec from 'k6/execution';
import { BASE_URL, expectOk } from './config.js';

/**
 * 비밀번호는 **코드에 넣지 않는다.** 이 리포는 public 이고, 시드 계정 비밀번호는 관리자 계정
 * 해시를 복사한 것이라 그대로 적으면 이미 있는 노출을 한 번 더 늘린다. `.env`(gitignore) 에서만 읽는다.
 */
export const LOGIN_PASSWORD = __ENV.LOGIN_PASSWORD || '';

/**
 * 시드 계정 `count` 개로 로그인해 액세스 토큰을 모은다.
 * 로그인 1회가 bcrypt 때문에 약 90ms 라 300개면 약 30초 — 그동안 앱이 워밍업되는 것은
 * 기준선 측정에 오히려 유리하다.
 */
export function issueTokens(count) {
  if (!LOGIN_PASSWORD) {
    exec.test.abort(
      'LOGIN_PASSWORD 가 없다. loadtest/.env 에 시드 계정 비밀번호를 넣을 것 ' +
      '(코드에 적지 않는다 — 이 리포는 public 이다).'
    );
  }
  const tokens = [];
  for (let i = 1; i <= count; i++) {
    const email = `load-${String(i).padStart(6, '0')}@loadtest.local`;
    const res = http.post(
      `${BASE_URL}/api/auth/login`,
      JSON.stringify({ email, password: LOGIN_PASSWORD }),
      {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'setup_login' },
        // 시나리오가 discardResponseBodies 를 켜므로 **이 요청만 본문을 되살린다** —
        // accessToken 을 꺼내야 하기 때문이다.
        responseType: 'text',
      }
    );
    expectOk(res, `login ${email}`);
    const token = res.json('data.accessToken');
    if (!token) exec.test.abort(`${email} 로그인 응답에 accessToken 이 없다.`);
    tokens.push(token);
  }
  return tokens;
}

/** VU 번호로 토큰 하나를 고른다. 계정이 VU 보다 적으면 돌려 쓴다. */
export function tokenFor(tokens) {
  return tokens[(exec.vu.idInTest - 1) % tokens.length];
}
