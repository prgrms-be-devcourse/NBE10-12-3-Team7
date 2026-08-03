import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import type { AuthedClient } from '../../fixtures/test';
import { unwrap } from '../../support/api';

/**
 * 상품 판매 흐름 — 등록한 상품을 상세에서 거래완료로 바꾸면 목록에서 사라진다
 *
 * 백엔드 검증은 specs/api/product.spec.ts 가 담당한다. 여기서는 판매자가 실제로 밟는 화면
 * (상세 → 상태 변경 → 목록)만 브라우저로 확인한다. 상품 등록은 화면이 아니라 API 로 끝낸다.
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
  const title = `e2e-ui-product-${Date.now()}`;
  const res = await seller.api.post('/api/products', {
    data: {
      categoryId: categories[0]!.id,
      title,
      description: 'e2e UI 판매 흐름 테스트용 상품',
      price: 25000,
      regionCode,
      imageUrls: ['https://example.com/e2e.jpg'],
      thumbnailIndex: 0,
    },
  });
  const created = await unwrap<{ productId: number }>(res, 201);
  return { productId: created.productId, title };
}

test.describe('상품 판매 — 거래완료로 바꾸면 목록에서 내려간다', () => {
  test('판매자가 상세에서 거래완료로 바꾸면 상품 목록에서 사라진다', async ({ page, user }) => {
    const product = await test.step('준비: 상품을 등록한다', async () => await createProduct(user));

    await test.step('로그인한다', async () => {
      await page.goto('/login');
      await page.getByLabel('이메일').fill(user.email);
      await page.getByLabel('비밀번호').fill(user.password);
      await page.getByRole('button', { name: '로그인', exact: true }).click();
      await page.waitForURL('**/products');
      // /products 진입 시 Access Token 재발급이 한 번 더 돈다(AuthBootstrap). 그 응답을 받기 전에
      // 하드 네비게이션하면 쿠키 경합이 생겨 살짝 안정화한다(specs/ui/trade.spec.ts 와 동일).
      await page.waitForTimeout(500);
    });

    // 상태 배지. 같은 글자가 상태 변경 <select> 의 <option> 에도 있어 span 으로 좁힌다.
    const statusBadge = (label: string) => page.locator('span').filter({ hasText: new RegExp(`^${label}$`) });

    await test.step('상품 상세에서 판매중 상태를 확인한다', async () => {
      await page.goto(`/products/${product.productId}`);
      await expect(page.getByRole('heading', { name: product.title })).toBeVisible();
      await expect(statusBadge('판매중')).toBeVisible();
    });

    await test.step('거래 상태를 거래완료로 바꾼다', async () => {
      await page.getByLabel('거래 상태 변경').selectOption('COMPLETED');
      await page.getByRole('button', { name: '상태 변경' }).click();
      await expect(statusBadge('거래완료')).toBeVisible();
    });

    await test.step('상품 목록에서는 더 이상 보이지 않는다', async () => {
      await page.goto('/products');
      await expect(page.getByText(product.title)).toHaveCount(0);
    });
  });
});
