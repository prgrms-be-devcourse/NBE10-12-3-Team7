import type { APIRequestContext } from '@playwright/test';
import { unwrap } from '../support/api';
import { ADMIN_PASSWORD } from '../support/env';
import { readEmailVerificationCode } from '../support/redis';
import { TEST_PASSWORD, uniqueEmail, uniqueNickname } from '../support/unique';

/**
 * 회원 생성/로그인 절차를 한 곳에 모은다.
 *
 * 거의 모든 시나리오가 "로그인한 사용자"에서 시작하는데, 그 준비 과정(이메일 인증 → 가입 → 로그인)을
 * 스펙마다 복사하면 인증 흐름이 바뀔 때 12개 파일을 동시에 고쳐야 한다. 여기만 고치면 되게 한다.
 */

export interface Account {
  memberId: number;
  email: string;
  password: string;
  nickname: string;
}

export interface Credentials extends Account {
  accessToken: string;
}

interface SignupResponse {
  memberId: number;
  email: string;
  nickname: string;
}

/**
 * 이메일 인증을 통과시킨다: 코드 발급 요청 → Redis 에서 코드 조회 → 확인.
 * 실제 백엔드 인증 경로를 그대로 밟기 때문에, 인증 로직이 깨지면 여기서 먼저 터진다.
 */
export async function verifyEmail(api: APIRequestContext, email: string): Promise<void> {
  const requested = await api.post('/api/auth/email-verifications', { data: { email } });
  await unwrap(requested, 201);

  const code = await readEmailVerificationCode(email);

  const confirmed = await api.post('/api/auth/email-verifications/confirm', {
    data: { email, code },
  });
  const result = await unwrap<{ email: string; verified: boolean }>(confirmed, 200);
  if (!result.verified) {
    throw new Error(`이메일 인증에 실패했습니다: ${email}`);
  }
}

/** 새 회원을 만든다(이메일 인증 포함). 로그인은 하지 않는다. */
export async function signUp(
  api: APIRequestContext,
  overrides: Partial<Pick<Account, 'email' | 'password' | 'nickname'>> = {},
): Promise<Account> {
  const email = overrides.email ?? uniqueEmail();
  const password = overrides.password ?? TEST_PASSWORD;
  const nickname = overrides.nickname ?? uniqueNickname();

  await verifyEmail(api, email);

  const res = await api.post('/api/auth/signup', {
    data: {
      email,
      password,
      nickname,
      termsAgreed: true,
      personalInfoCollectionAgreed: true,
    },
  });
  const created = await unwrap<SignupResponse>(res, 201);

  return { memberId: created.memberId, email, password, nickname };
}

/**
 * 로그인해서 Access Token 을 받는다.
 * Refresh Token 은 HttpOnly 쿠키로 내려가므로, 재발급까지 검증할 스펙은 같은
 * APIRequestContext 를 계속 써야 쿠키가 유지된다(Playwright 가 쿠키 저장소를 컨텍스트 단위로 관리).
 */
export async function login(
  api: APIRequestContext,
  email: string,
  password: string,
  autoLogin = false,
): Promise<string> {
  const res = await api.post('/api/auth/login', { data: { email, password, autoLogin } });
  const { accessToken } = await unwrap<{ accessToken: string }>(res, 200);
  return accessToken;
}

/** 가입 + 로그인을 한 번에. 대부분의 스펙이 쓰는 진입점. */
export async function signUpAndLogin(api: APIRequestContext): Promise<Credentials> {
  const account = await signUp(api);
  const accessToken = await login(api, account.email, account.password);
  return { ...account, accessToken };
}

/** Authorization 헤더를 만든다. 인증 스킴이 바뀌면 여기만 고친다. */
export function bearer(accessToken: string): Record<string, string> {
  return { Authorization: `Bearer ${accessToken}` };
}

/**
 * 관리자 계정. `AdminSeeder` 가 만든다 — 단 **dev 프로파일 + `APP_SEED_ADMIN=true`** 일 때만이다
 * (docker-compose.e2e.yml 이 그 두 조건과 비밀번호를 함께 넣는다). 예전처럼 "test 만 아니면
 * 항상 생기는" 계정이 아니므로, 호스트 백엔드로 e2e 를 돌릴 때도 같은 스위치를 켜야 한다.
 *
 * 비밀번호는 [ADMIN_PASSWORD] 한 곳에서만 읽는다 — compose 와 기본값이 같아야 한다.
 * 이메일은 비밀이 아니라 상수로 둔다.
 *
 * ⚠️ 이 계정은 **전역 공유 자원**이다. 여러 테스트가 동시에 admin 으로 로그인하는 것은 괜찮지만,
 *    제재·삭제 같은 쓰기 작업의 *대상*은 반드시 그 테스트가 새로 만든 회원이어야 한다.
 *    남이 만든 데이터를 건드리면 병렬 실행에서 서로를 깨뜨린다.
 */
export const ADMIN_ACCOUNT = {
  email: 'admin@dongnemarket.com',
  password: ADMIN_PASSWORD,
} as const;

/** 시더가 만든 관리자로 로그인한다. */
export async function loginAsAdmin(api: APIRequestContext): Promise<string> {
  return login(api, ADMIN_ACCOUNT.email, ADMIN_ACCOUNT.password);
}
