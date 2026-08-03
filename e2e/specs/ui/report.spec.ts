import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import type { AuthedClient } from '../../fixtures/test';
import { unwrap } from '../../support/api';

/**
 * 신고 — 상품 상세에서 접수한 신고가 신고내역 화면에 보인다
 *
 * 준비(로그인 상태 만들기 전까지)는 전부 API로 하고, 실제로 검증할 화면 여정(신고 접수 → 신고내역
 * 확인)만 브라우저로 조작한다 — 회원가입을 브라우저로 하면 이메일 인증 때문에 느려지고, 그 과정에서
 * 깨지면 정작 보려는 화면과 무관한 곳에서 실패한다.
 */

async function pickDongRegionCode(api: APIRequestContext): Promise<string> {
  const provinces = await unwrap<{ code: string }[]>(await api.get('/api/regions'), 200);
  const districts = await unwrap<{ code: string }[]>(
    await api.get(`/api/regions?parentCode=${provinces[0]!.code}`),
    200,
  );
  const dongs = await unwrap<{ code: string }[]>(
    await api.get(`/api/regions?parentCode=${districts[0]!.code}`),
    200,
  );
  return dongs[0]!.code;
}

/** 신고할 상품 하나를 만든다. 로그인해서 신고를 접수할 계정과는 다른 판매자여야 한다. */
async function createProduct(seller: AuthedClient): Promise<{ productId: number; title: string }> {
  const [categories, regionCode] = await Promise.all([
    unwrap<{ id: number }[]>(await seller.api.get('/api/categories'), 200),
    pickDongRegionCode(seller.api),
  ]);
  const title = `e2e-ui-report-${Date.now()}`;
  const res = await seller.api.post('/api/products', {
    data: {
      categoryId: categories[0]!.id,
      title,
      description: 'e2e UI 신고 테스트용 상품',
      price: 10000,
      regionCode,
      imageUrls: ['https://example.com/e2e.jpg'],
      thumbnailIndex: 0,
    },
  });
  const created = await unwrap<{ productId: number }>(res, 201);
  return { productId: created.productId, title };
}

test.describe('신고 — 상품 상세에서 접수하면 신고내역에서 확인된다', () => {
  test('로그인한 사용자가 상품 상세에서 신고를 접수하면 신고내역 페이지에 나타난다', async ({
    page,
    user,
    otherUser,
  }) => {
    const product = await test.step('준비: 다른 사용자가 판매하는 상품을 만든다', async () => createProduct(otherUser));

    await test.step('로그인한다', async () => {
      await page.goto('/login');
      await page.getByLabel('이메일').fill(user.email);
      await page.getByLabel('비밀번호').fill(user.password);
      await page.getByRole('button', { name: '로그인', exact: true }).click();
      await page.waitForURL('**/products');
      // /products 자체가 진입 시 Access Token 재발급을 한 번 더 시도한다(AuthBootstrap). 그 요청이
      // 끝나기 전에 곧바로 다음 페이지로 하드 네비게이션하면, 서버는 이미 Refresh Token을 회전시켰는데
      // 브라우저는 그 응답(Set-Cookie)을 못 받아 무효화된 옛 쿠키만 남는 경합이 생긴다. 살짝 안정화한다.
      await page.waitForTimeout(500);
    });

    await test.step('상품 상세에서 신고를 접수한다', async () => {
      await page.goto(`/products/${product.productId}`);
      await page.getByRole('heading', { name: product.title }).waitFor();

      await page.getByRole('button', { name: '상품 신고' }).click();
      await page.waitForURL('**/report**');

      await page.getByRole('radio', { name: '사기 의심 (허위 매물·거래 사기)' }).click();
      await page.getByLabel('신고 내용').fill('e2e UI 스펙: 사기가 의심됩니다.');
      await page.getByRole('button', { name: '신고하기' }).click();

      await page.waitForURL('**/my-reports');
    });

    await test.step('신고내역 페이지에서 방금 접수한 신고가 보인다', async () => {
      await expect(page.getByText(product.title)).toBeVisible();
    });
  });
});
