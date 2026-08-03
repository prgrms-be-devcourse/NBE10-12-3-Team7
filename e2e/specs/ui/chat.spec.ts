import type { APIRequestContext, Page } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';

/**
 * 채팅 — 화면 여정 (권건우) : 로그인 → 상품 상세에서 채팅 시작 → 메시지 전송 → 대화에 보인다
 *
 * 대표 여정 하나만 본다(§4). 권한·멱등성 계약은 specs/api/chat.spec.ts 가 담당한다.
 * 준비(회원·상품)는 API 로, 검증만 브라우저로 한다(§9).
 *
 * 소유권: '채팅으로 거래하기' 버튼은 비소유자에게만 보인다 → 구매자(user)가 판매자(otherUser) 상품에서 연다.
 * 채팅 시작은 router.push(클라이언트 이동)라 인메모리 토큰이 유지된다 — 채팅방에서 재복구 대기가 필요 없다.
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

async function createProduct(api: APIRequestContext): Promise<number> {
  const categories = await unwrap<{ id: number }[]>(await api.get('/api/categories'), 200);
  const regionCode = await pickDongRegionCode(api);
  const created = await unwrap<{ productId: number }>(
    await api.post('/api/products', {
      data: {
        categoryId: categories[0].id,
        title: uniqueTitle('상품'),
        description: 'e2e 채팅 화면 여정용',
        price: 10_000,
        regionCode,
        imageUrls: ['https://e2e.local/sample.jpg'],
        thumbnailIndex: 0,
      },
    }),
    201,
  );
  return created.productId;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('이메일').fill(email);
  await page.getByLabel('비밀번호').fill(password);
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await page.waitForURL('**/products');
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible({ timeout: 15_000 });
}

test('구매자가 상품 상세에서 채팅을 시작해 메시지를 보내면 대화에 보인다', async ({
  page,
  user,
  otherUser,
}) => {
  const productId = await createProduct(otherUser.api);

  await test.step('로그인한다', async () => {
    await loginViaUi(page, user.email, user.password);
  });

  await test.step('상품 상세에서 채팅을 시작한다', async () => {
    await page.goto(`/products/${productId}`);
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible({ timeout: 15_000 });
    await page.getByRole('button', { name: '채팅으로 거래하기' }).click();
    await page.waitForURL('**/chat/**');
  });

  await test.step('메시지를 보내면 대화창에 보인다', async () => {
    const message = uniqueTitle('메시지');
    const input = page.getByPlaceholder('메시지를 입력하세요');
    await expect(input).toBeVisible();
    await input.fill(message);
    // 전송 버튼은 입력이 비어 있으면 disabled 라, fill 후에 눌러야 한다.
    await page.getByRole('button', { name: '전송' }).click();

    await expect(page.getByText(message)).toBeVisible();
  });
});
