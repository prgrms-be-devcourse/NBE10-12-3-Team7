import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import type { AuthedClient } from '../../fixtures/test';
import { unwrap } from '../../support/api';

/**
 * 거래내역 — 거래완료 처리한 상품이 내정보 페이지의 판매내역에 나타난다
 *
 * 상품 등록·거래완료 처리는 API로 끝내고, 실제로 검증할 화면(내정보 → 거래내역 → 판매내역)만
 * 브라우저로 확인한다.
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

async function createProduct(seller: AuthedClient): Promise<{ productId: number; title: string }> {
  const [categories, regionCode] = await Promise.all([
    unwrap<{ id: number }[]>(await seller.api.get('/api/categories'), 200),
    pickDongRegionCode(seller.api),
  ]);
  const title = `e2e-ui-trade-${Date.now()}`;
  const res = await seller.api.post('/api/products', {
    data: {
      categoryId: categories[0]!.id,
      title,
      description: 'e2e UI 거래내역 테스트용 상품',
      price: 18000,
      regionCode,
      imageUrls: ['https://example.com/e2e.jpg'],
      thumbnailIndex: 0,
    },
  });
  const created = await unwrap<{ productId: number }>(res, 201);
  return { productId: created.productId, title };
}

test.describe('거래내역 — 완료된 거래가 내정보 화면에 표시된다', () => {
  test('판매자가 거래완료 처리한 상품이 내정보의 판매내역 탭에 나타난다', async ({ page, user }) => {
    const product = await test.step('준비: 상품을 등록하고 거래완료로 전환한다', async () => {
      const created = await createProduct(user);
      await unwrap(
        await user.api.patch(`/api/products/${created.productId}/status`, { data: { tradeStatus: 'COMPLETED' } }),
        200,
      );
      return created;
    });

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

    await test.step('내정보 페이지의 판매내역(기본 탭)에서 확인된다', async () => {
      await page.goto('/my-profile');
      await expect(page.getByRole('button', { name: '판매내역' })).toBeVisible();
      await expect(page.getByText(product.title)).toBeVisible();
    });
  });
});
