import type { PlaywrightWorkerArgs } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { expectError, readSetCookie, unwrap } from '../../support/api';
import { bearer, signUp } from '../../fixtures/auth';
import { BACKEND_URL } from '../../support/env';

/**
 * 회원 — 계정 상태 전이 (비밀번호 변경·탈퇴)
 *
 * 왜 e2e 인가: 상태 전이 축. 두 시나리오 모두 member 테이블만이 아니라 인증 저장소(Redis
 * Refresh Token)까지 함께 움직여야 하는 계약이다:
 *  - 비밀번호 변경 성공 → 저장된 Refresh Token 삭제 (모든 세션 재로그인 유도, MemberService)
 *  - 탈퇴(softDelete) → 로그인·재발급·조회가 전부 거부 (login/reissue 의 상태 검증과 일관)
 * 단위 테스트는 이 둘을 각자 계층에서만 보므로, 계층을 가로지르는 이 계약은 여기서만 검증된다.
 *
 * user 픽스처 대신 api 컨텍스트에서 직접 로그인하는 이유: Refresh Token 쿠키가 이 컨텍스트의
 * 쿠키 저장소에 남아 있어야 "변경/탈퇴 후 재발급" 경로를 검증할 수 있다.
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

test.describe('회원 — 계정 상태 전이', () => {
  test('비밀번호를 변경하면 옛 비밀번호는 거부되고, 기존 세션의 Refresh Token 도 무효화된다', async ({
    api,
    playwright,
  }) => {
    const account = await signUp(api);
    const newPassword = 'E2eNewPass!567';

    const loginRes = await api.post('/api/auth/login', {
      data: { email: account.email, password: account.password, autoLogin: true },
    });
    const { accessToken } = await unwrap<{ accessToken: string }>(loginRes, 200);
    const refreshTokenBeforeChange = readSetCookie(loginRes, 'refreshToken')!;
    const auth = bearer(accessToken);

    await test.step('현재 비밀번호가 틀리면 거부된다', async () => {
      const res = await api.patch('/api/members/me/password', {
        headers: auth,
        data: { currentPassword: 'Wrong!Pass99', newPassword },
      });
      await expectError(res, 401, 'INVALID_PASSWORD');
    });

    await test.step('새 비밀번호가 기존과 같으면 거부된다', async () => {
      const res = await api.patch('/api/members/me/password', {
        headers: auth,
        data: { currentPassword: account.password, newPassword: account.password },
      });
      await expectError(res, 400, 'SAME_AS_OLD_PASSWORD');
    });

    await test.step('올바른 현재 비밀번호로는 변경에 성공한다', async () => {
      const res = await api.patch('/api/members/me/password', {
        headers: auth,
        data: { currentPassword: account.password, newPassword },
      });
      await unwrap(res, 200);
    });

    await test.step('변경 전 Refresh Token 은 삭제되어 재발급이 거부된다 — 세션 무효화 계약', async () => {
      const { res, dispose } = await reissueWithToken(playwright, refreshTokenBeforeChange);
      await expectError(res, 401, 'REFRESH_TOKEN_NOT_FOUND');
      await dispose();
    });

    await test.step('옛 비밀번호 로그인은 거부되고, 새 비밀번호로는 로그인된다', async () => {
      const oldAttempt = await api.post('/api/auth/login', {
        data: { email: account.email, password: account.password, autoLogin: false },
      });
      await expectError(oldAttempt, 401, 'INVALID_PASSWORD');

      const newAttempt = await api.post('/api/auth/login', {
        data: { email: account.email, password: newPassword, autoLogin: false },
      });
      await unwrap(newAttempt, 200);
    });
  });

  test('탈퇴한 회원은 로그인·토큰 재발급·내 정보 조회가 모두 거부된다', async ({ api, playwright }) => {
    const account = await signUp(api);

    const loginRes = await api.post('/api/auth/login', {
      data: { email: account.email, password: account.password, autoLogin: true },
    });
    const { accessToken } = await unwrap<{ accessToken: string }>(loginRes, 200);
    const refreshToken = readSetCookie(loginRes, 'refreshToken')!;
    const auth = bearer(accessToken);

    await test.step('탈퇴 요청은 성공한다', async () => {
      const res = await api.delete('/api/members/me', { headers: auth });
      await unwrap(res, 200);
    });

    await test.step('재로그인이 거부된다', async () => {
      const res = await api.post('/api/auth/login', {
        data: { email: account.email, password: account.password, autoLogin: false },
      });
      await expectError(res, 400, 'DELETED_MEMBER');
    });

    await test.step('탈퇴 전 발급받은 Refresh Token 으로도 재발급이 거부된다', async () => {
      const { res, dispose } = await reissueWithToken(playwright, refreshToken);
      await expectError(res, 400, 'DELETED_MEMBER');
      await dispose();
    });

    await test.step('탈퇴 전 발급받은 Access Token 으로도 내 정보 조회가 거부된다', async () => {
      const res = await api.get('/api/members/me', { headers: auth });
      await expectError(res, 400, 'DELETED_MEMBER');
    });
  });
});
