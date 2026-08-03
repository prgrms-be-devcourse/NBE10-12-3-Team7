import type { APIRequestContext, Page } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';

/**
 * 알림 — 화면 여정 (권건우) : 내 상품에 남이 댓글 → 헤더 알림 패널에 뜬다
 *
 * 대표 여정 하나만 본다(§4). 파생 규칙·크로스도메인 계약은 specs/api/notification.spec.ts 가 담당한다.
 * 알림은 "남이 내 상품에 댓글" 이벤트에서 파생되고(동기, AFTER_COMMIT), 수신자는 상품 주인이다.
 * 그래서 user 가 상품을 올리고(주인), otherUser 가 댓글을 단다 → user 헤더 알림에 뜬다.
 *
 * 준비(회원·상품·댓글)는 API 로, 검증만 브라우저로 한다(§9).
 */

async function pickDongRegionCode(api: APIRequestContext): Promise<string> {
  const sido = await unwrap<{ code: string }[]>(await api.get('/api/regions'), 200);
  const sigungu = await unwrap<{ code: string }[]>(
    await api.get(`/api/regions?parentCode=${sido[0].code}`),
    200,
  );
  const dong = await unwrap<{ code: string }[]>(
    await api.get(`/api/regions?parentCode=${sigungu[0].code}`),
    200,
  );
  return dong[0].code;
}

/** 상품을 만들고 productId·title 을 돌려준다. title 은 알림 메시지("...글에 새 댓글")에 그대로 들어가 화면에서 식별에 쓴다. */
async function createProduct(api: APIRequestContext): Promise<{ productId: number; title: string }> {
  const categories = await unwrap<{ id: number }[]>(await api.get('/api/categories'), 200);
  const regionCode = await pickDongRegionCode(api);
  const title = uniqueTitle('알림상품');
  const created = await unwrap<{ productId: number }>(
    await api.post('/api/products', {
      data: {
        categoryId: categories[0].id,
        title,
        description: 'e2e 알림 화면 여정용',
        price: 10_000,
        regionCode,
        imageUrls: ['https://e2e.local/sample.jpg'],
        thumbnailIndex: 0,
      },
    }),
    201,
  );
  return { productId: created.productId, title };
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('이메일').fill(email);
  await page.getByLabel('비밀번호').fill(password);
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await page.waitForURL('**/products');
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible({ timeout: 15_000 });
}

test('내 상품에 남이 댓글을 달면 헤더 알림 패널에 뜬다', async ({ page, user, otherUser }) => {
  // user 가 상품 주인이어야 알림 수신자가 된다(자기 댓글은 알림 없음).
  const { productId, title } = await createProduct(user.api);

  await test.step('상품 주인(user)이 로그인한다', async () => {
    await loginViaUi(page, user.email, user.password);
  });

  await test.step('남(otherUser)이 내 상품에 댓글을 단다(API)', async () => {
    await unwrap(
      await otherUser.api.post(`/api/products/${productId}/comments`, {
        data: { content: '이 상품 아직 판매하나요?' },
      }),
      201,
    );
  });

  await test.step('헤더 알림 패널을 열면 그 상품에 대한 알림이 보인다', async () => {
    await page.getByRole('button', { name: '알림' }).click();
    // 알림 메시지: 💬 "{상품명}" 글에 새로운 댓글이 작성되었습니다. → 패널 안에서 상품명으로 식별한다.
    // 뒤 배경 /products 목록에도 상품명이 있을 수 있어 패널(.notif-panel)로 범위를 좁힌다.
    await expect(page.locator('.notif-panel').getByText(title)).toBeVisible();
  });
});
