import { test as base, expect, type APIRequestContext, type PlaywrightWorkerArgs } from '@playwright/test';
import { bearer, loginAsAdmin, signUpAndLogin, type Credentials } from './auth';
import { BACKEND_URL } from '../support/env';

/**
 * 확장된 test 객체. 모든 스펙은 '@playwright/test' 대신 이 파일에서 test/expect 를 가져온다.
 *
 *   import { test, expect } from '../../fixtures/test';
 *
 * 제공하는 픽스처
 *   api       — 비로그인 백엔드 클라이언트 (401/인증 흐름 검증용)
 *   user      — 갓 가입·로그인한 일반 회원 + 토큰이 박힌 클라이언트
 *   otherUser — 또 다른 일반 회원. "남의 리소스 건드리면 403" 검증에 쓴다
 *   admin     — AdminSeeder 가 만든 관리자. 전역 공유 계정이라 쓰기 대상에 주의
 *
 * 픽스처는 실제로 참조한 테스트에서만 생성된다. otherUser 를 안 쓰는 스펙은 회원가입 비용을 치르지 않는다.
 */

/** 로그인 상태가 준비된 사용자. api 로 요청하면 Authorization 헤더가 자동으로 붙는다. */
export interface AuthedClient extends Credentials {
  api: APIRequestContext;
}

async function createAuthedClient(
  playwright: PlaywrightWorkerArgs['playwright'],
): Promise<AuthedClient> {
  // 가입·로그인 자체는 토큰이 없는 상태에서 해야 하므로 임시 컨텍스트를 쓴다.
  const bootstrap = await playwright.request.newContext({ baseURL: BACKEND_URL });
  const credentials = await signUpAndLogin(bootstrap);
  await bootstrap.dispose();

  const api = await playwright.request.newContext({
    baseURL: BACKEND_URL,
    extraHTTPHeaders: bearer(credentials.accessToken),
  });
  return { ...credentials, api };
}

export const test = base.extend<{
  api: APIRequestContext;
  user: AuthedClient;
  otherUser: AuthedClient;
  admin: APIRequestContext;
}>({
  api: async ({ playwright }, use) => {
    const context = await playwright.request.newContext({ baseURL: BACKEND_URL });
    await use(context);
    await context.dispose();
  },

  user: async ({ playwright }, use) => {
    const client = await createAuthedClient(playwright);
    await use(client);
    await client.api.dispose();
  },

  otherUser: async ({ playwright }, use) => {
    const client = await createAuthedClient(playwright);
    await use(client);
    await client.api.dispose();
  },

  admin: async ({ playwright }, use) => {
    const bootstrap = await playwright.request.newContext({ baseURL: BACKEND_URL });
    const accessToken = await loginAsAdmin(bootstrap);
    await bootstrap.dispose();

    const context = await playwright.request.newContext({
      baseURL: BACKEND_URL,
      extraHTTPHeaders: bearer(accessToken),
    });
    await use(context);
    await context.dispose();
  },
});

export { expect };
