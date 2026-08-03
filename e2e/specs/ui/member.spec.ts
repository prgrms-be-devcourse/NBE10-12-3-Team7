import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { signUp } from '../../fixtures/auth';

/**
 * 회원 — 비밀번호 변경 화면 여정
 *
 * 왜 e2e 인가: 상태 전이 축. 비밀번호 변경은 member 테이블만이 아니라 인증 저장소(Redis
 * Refresh Token)까지 함께 움직이는 전이인데, 화면에서는 그 결과가 "강제 재로그인"으로
 * 나타난다: 변경 성공 → 로그인 화면으로 이동 → 이전 세션으로는 어떤 인증 페이지도 못 들어감
 * → 새 비밀번호로만 재입장. 이 흐름이 브라우저에서 실제로 이어지는지는 여기서만 검증된다.
 * (백엔드 계약 자체는 specs/api/member.spec.ts 가 검증한다.)
 *
 * 준비(회원 생성)는 API 로 하고, 브라우저는 검증에만 쓴다.
 */

/** 로그인 화면에서 로그인한다. 성공하면 상품 목록으로 이동한 상태로 돌아온다. */
async function loginViaScreen(page: Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('이메일').fill(email);
  await page.getByLabel('비밀번호').fill(password);
  // "카카오로 로그인"·"구글로 로그인" 버튼과 substring 매칭되므로 exact 로 집는다.
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await page.waitForURL('**/products');
  // 페이지 로드마다 부트스트랩이 Refresh Token 을 회전시킨다. 헤더가 로그인 상태로 바뀔
  // 때까지가 회전 완료 신호다 — 그 전에 다른 페이지로 이동하면 회전 응답(새 쿠키)을 버려서
  // 브라우저에 옛 토큰만 남고, 다음 재발급이 거부된다.
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
}

test.describe('회원 — 비밀번호 변경 여정', () => {
  test('화면에서 비밀번호를 변경하면 재로그인을 요구하고, 새 비밀번호로만 다시 들어올 수 있다', async ({
    page,
    api,
  }) => {
    const account = await signUp(api);
    const newPassword = 'E2eNewPass!567';

    await test.step('로그인해 내 정보 화면에 들어가면 내 계정이 보인다', async () => {
      await loginViaScreen(page, account.email, account.password);
      await page.goto('/my-profile');
      await expect(page.getByText(account.email)).toBeVisible();
    });

    await test.step('비밀번호 변경 폼을 제출하면 로그인 화면으로 돌려보낸다', async () => {
      // "새 비밀번호" 라벨은 "새 비밀번호 확인"의 접두사라 non-exact 매칭이 중복된다.
      // 라벨의 필수 표시(*)까지 포함해 exact 로 집는다.
      await page.getByLabel('현재 비밀번호').fill(account.password);
      await page.getByLabel('새 비밀번호*', { exact: true }).fill(newPassword);
      await page.getByLabel('새 비밀번호 확인*', { exact: true }).fill(newPassword);
      await page.getByRole('button', { name: '비밀번호 변경' }).click();
      await page.waitForURL('**/login');
    });

    await test.step('이전 세션은 무효화되어 인증 페이지에 다시 못 들어간다', async () => {
      // 브라우저에는 변경 전 Refresh Token 쿠키가 남아 있지만, 서버가 토큰을 삭제했으므로
      // 재발급이 거부되고 로그인 화면으로 돌려보내져야 한다 — 전 세션 로그아웃 계약.
      await page.goto('/my-profile');
      await page.waitForURL('**/login');
    });

    await test.step('새 비밀번호로는 다시 로그인된다', async () => {
      await loginViaScreen(page, account.email, newPassword);
      await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
    });
  });
});
