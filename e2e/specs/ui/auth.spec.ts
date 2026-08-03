import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { signUp } from '../../fixtures/auth';

/**
 * 인증 — 브라우저 로그인 세션 여정
 *
 * 왜 e2e 인가: 권한 축. 프론트는 Access Token 을 메모리에만 보관하고(lib/auth.ts),
 * 새로고침 후 복구는 HttpOnly Refresh Token 쿠키 → 재발급(bootstrapAutoLogin)에 전적으로
 * 의존한다. "새로고침해도 로그인이 유지된다 / 로그아웃하면 서버 세션까지 끊긴다"는
 * 브라우저 쿠키 저장소·페이지 리로드가 실제로 개입해야만 검증되는 계약이라 API 스펙으로는
 * 대신할 수 없다.
 *
 * 준비(회원 생성)는 API 로 하고, 브라우저는 검증에만 쓴다.
 */

/** 로그인 화면에서 로그인한다. 성공하면 상품 목록으로 이동한 상태로 돌아온다. */
async function loginViaScreen(page: Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('이메일').fill(email);
  await page.getByLabel('비밀번호').fill(password);
  await page.getByLabel('자동 로그인').check();
  // "카카오로 로그인"·"구글로 로그인" 버튼과 substring 매칭되므로 exact 로 집는다.
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await page.waitForURL('**/products');
  // 페이지 로드마다 부트스트랩이 Refresh Token 을 회전시킨다. 헤더가 로그인 상태로 바뀔
  // 때까지가 회전 완료 신호다 — 그 전에 다른 페이지로 이동하면 회전 응답(새 쿠키)을 버려서
  // 브라우저에 옛 토큰만 남고, 다음 재발급이 거부된다.
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
}

test.describe('인증 — 로그인 세션 여정', () => {
  test('로그인 세션은 새로고침을 견디고, 로그아웃하면 서버 세션까지 함께 끊긴다', async ({
    page,
    api,
  }) => {
    const account = await signUp(api);

    await test.step('로그인하면 상품 목록으로 이동하고 헤더가 로그인 상태로 바뀐다', async () => {
      await loginViaScreen(page, account.email, account.password);
      await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
    });

    await test.step('새로고침해도 로그인이 유지된다 — Refresh 쿠키 → 재발급 복구 계약', async () => {
      // Access Token 은 메모리에만 있어 리로드 순간 사라진다. 여기서 로그인 상태가
      // 살아나면 HttpOnly 쿠키 → reissue 경로가 실제로 동작한 것이다.
      await page.reload();
      await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
    });

    await test.step('로그아웃하면 로그인 화면으로 이동하고 헤더가 비로그인 상태로 바뀐다', async () => {
      await page.getByRole('button', { name: '로그아웃' }).click();
      await page.waitForURL('**/login');
      await expect(page.getByRole('link', { name: '로그인' })).toBeVisible();
    });

    await test.step('로그아웃 후 인증 페이지에 가면 로그인 화면으로 돌려보내진다 — 서버 세션도 죽었다', async () => {
      // 쿠키가 남아 있어도 서버의 Refresh Token 이 삭제됐다면 재발급이 거부되어야 한다.
      await page.goto('/my-profile');
      await page.waitForURL('**/login');
    });
  });
});
