import type { PlaywrightWorkerArgs } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { expectError, readSetCookie, unwrap } from '../../support/api';
import { bearer, signUp } from '../../fixtures/auth';
import { BACKEND_URL } from '../../support/env';

/**
 * 인증 — Refresh Token 수명주기 (회전·폐기)
 *
 * 왜 e2e 인가: 권한 축. Refresh Token 은 HttpOnly 쿠키 ↔ Redis 저장소 ↔ 시큐리티 필터에
 * 걸쳐 있어서 단위 테스트로는 계약이 검증되지 않는다. 코틀린 전환(단계 6·7)이 바로 이
 * 경로를 옮겼으므로, 전환이 동작을 바꾸지 않았음을 여기서 증명한다.
 *
 * 쿠키 저장소는 APIRequestContext 단위이므로, 로그인~재발급을 한 컨텍스트(api)에서 이어서 한다.
 * "이전 토큰 재사용" 검증은 컨텍스트 쿠키가 끼어들지 않도록 쿠키 없는 컨텍스트에서 보낸다.
 */

/** 컨텍스트 쿠키 저장소를 우회해, 지정한 Refresh Token 하나만 실어 재발급을 시도한다. */
async function reissueWithToken(
  playwright: PlaywrightWorkerArgs['playwright'],
  refreshToken: string,
) {
  const bare = await playwright.request.newContext({
    baseURL: BACKEND_URL,
    extraHTTPHeaders: { Cookie: `refreshToken=${refreshToken}` },
  });
  const res = await bare.post('/api/auth/reissue');
  return { res, dispose: () => bare.dispose() };
}

test.describe('인증 — Refresh Token 수명주기', () => {
  test('재발급하면 Refresh Token이 회전되고, 이전 토큰은 더 쓸 수 없다', async ({ api, playwright }) => {
    const account = await signUp(api);

    const oldRefreshToken = await test.step('로그인하면 Refresh Token이 HttpOnly 쿠키로 내려온다', async () => {
      const res = await api.post('/api/auth/login', {
        data: { email: account.email, password: account.password, autoLogin: true },
      });
      await unwrap(res, 200);
      const cookie = readSetCookie(res, 'refreshToken');
      expect(cookie, 'refreshToken 쿠키가 내려오지 않았다').toBeTruthy();
      return cookie!;
    });

    const newAccessToken = await test.step('재발급하면 새 토큰 쌍이 내려온다 (회전)', async () => {
      const res = await api.post('/api/auth/reissue');
      const { accessToken } = await unwrap<{ accessToken: string }>(res, 200);

      const rotated = readSetCookie(res, 'refreshToken');
      expect(rotated, '재발급 응답에 새 refreshToken 쿠키가 없다').toBeTruthy();
      expect(rotated, 'Refresh Token이 회전되지 않고 그대로 재사용됐다').not.toBe(oldRefreshToken);
      return accessToken;
    });

    await test.step('새 Access Token 은 실제로 동작한다', async () => {
      const me = await api.get('/api/members/me', { headers: bearer(newAccessToken) });
      const info = await unwrap<{ email: string }>(me, 200);
      expect(info.email).toBe(account.email);
    });

    await test.step('회전 전의 이전 Refresh Token 으로는 재발급이 거부된다', async () => {
      const { res, dispose } = await reissueWithToken(playwright, oldRefreshToken);
      await expectError(res, 401, 'INVALID_REFRESH_TOKEN');
      await dispose();
    });

    await test.step('회전된 새 Refresh Token 으로는 다시 재발급된다', async () => {
      // api 컨텍스트의 쿠키 저장소에는 직전 재발급이 내려준 새 토큰이 들어 있다.
      const res = await api.post('/api/auth/reissue');
      await unwrap<{ accessToken: string }>(res, 200);
    });
  });

  test('로그아웃하면 서버의 Refresh Token이 삭제되어 재발급이 거부된다', async ({ api, playwright }) => {
    const account = await signUp(api);

    const loginRes = await api.post('/api/auth/login', {
      data: { email: account.email, password: account.password, autoLogin: true },
    });
    const { accessToken } = await unwrap<{ accessToken: string }>(loginRes, 200);
    const refreshToken = readSetCookie(loginRes, 'refreshToken')!;
    expect(refreshToken).toBeTruthy();

    await test.step('로그아웃하면 쿠키가 즉시 만료된다', async () => {
      const res = await api.post('/api/auth/logout', { headers: bearer(accessToken) });
      await unwrap(res, 200);
      expect(readSetCookie(res, 'refreshToken'), '로그아웃 응답이 쿠키를 비우지 않았다').toBe('');
    });

    await test.step('쿠키를 백업해뒀더라도 서버에서 삭제되어 재발급이 거부된다', async () => {
      const { res, dispose } = await reissueWithToken(playwright, refreshToken);
      await expectError(res, 401, 'REFRESH_TOKEN_NOT_FOUND');
      await dispose();
    });

    await test.step('로그아웃은 멱등이다 — 다시 호출해도 성공한다', async () => {
      const res = await api.post('/api/auth/logout', { headers: bearer(accessToken) });
      await unwrap(res, 200);
    });
  });
});
