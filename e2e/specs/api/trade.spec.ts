import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import type { AuthedClient } from '../../fixtures/test';
import { unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';

/**
 * 거래내역 — 거래완료 상태 집계 · 판매/구매 관점 분리
 *
 * 왜 e2e 인가: 상태전이 축(거래완료된 상품만 집계) + 권한 축(내 거래내역에 남의 거래가 섞이면 안 된다).
 * 별도 엔티티 없이 product·chat 두 도메인을 읽기 전용으로 조합한 결과라, 실제로 이어 붙였을 때만
 * 드러나는 집계 오류를 여기서 잡는다.
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

async function createProduct(
  seller: AuthedClient,
  title: string,
  price: number,
): Promise<{ productId: number }> {
  const [categories, regionCode] = await Promise.all([
    unwrap<{ id: number }[]>(await seller.api.get('/api/categories'), 200),
    pickDongRegionCode(seller.api),
  ]);
  const res = await seller.api.post('/api/products', {
    data: {
      categoryId: categories[0]!.id,
      title,
      description: 'e2e 거래내역 테스트용 상품',
      price,
      regionCode,
      imageUrls: ['https://example.com/e2e.jpg'],
      thumbnailIndex: 0,
    },
  });
  const created = await unwrap<{ productId: number }>(res, 201);
  return { productId: created.productId };
}

async function completeTrade(seller: AuthedClient, productId: number): Promise<void> {
  const res = await seller.api.patch(`/api/products/${productId}/status`, {
    data: { tradeStatus: 'COMPLETED' },
  });
  await unwrap(res, 200);
}

test.describe('거래내역 — 상태 집계 · 관점 분리', () => {
  test('판매자가 거래완료 처리한 상품만 판매내역에 집계되고, 판매중인 상품은 제외된다', async ({ user }) => {
    const onSaleTitle = uniqueTitle('e2e-trade-on-sale');
    const completedTitle = uniqueTitle('e2e-trade-completed');

    const [onSale, completed] = await test.step('준비: 판매중 상품 1개 · 거래완료 상품 1개를 만든다', async () => {
      const onSaleProduct = await createProduct(user, onSaleTitle, 15000);
      const completedProduct = await createProduct(user, completedTitle, 25000);
      await completeTrade(user, completedProduct.productId);
      return [onSaleProduct, completedProduct];
    });

    await test.step('판매내역에는 거래완료 상품만 잡히고 판매중 상품은 빠진다', async () => {
      const sales = await unwrap<{ productId: number; title: string }[]>(
        await user.api.get('/api/members/me/trades/sales'),
        200,
      );
      const ids = sales.map((s) => s.productId);
      expect(ids, '거래완료 상품이 판매내역에 없다').toContain(completed.productId);
      expect(ids, '판매중 상품이 판매내역에 잘못 섞여 들어왔다').not.toContain(onSale.productId);
    });
  });

  test('내 거래내역에는 다른 회원의 거래가 섞이지 않고, 인증 없이는 조회할 수 없다', async ({ api, user, otherUser }) => {
    await test.step('토큰 없이 조회하면 401', async () => {
      expect((await api.get('/api/members/me/trades/sales')).status()).toBe(401);
      expect((await api.get('/api/members/me/trades/purchases')).status()).toBe(401);
    });

    // otherUser = 판매자, user = 구매자.
    const product = await test.step('준비: 판매자가 상품을 등록하고, 구매자가 채팅방을 연 뒤 거래가 완료된다', async () => {
      const created = await createProduct(otherUser, uniqueTitle('e2e-trade-cross'), 30000);
      await unwrap(await user.api.post('/api/chat-rooms', { data: { productId: created.productId } }), 200);
      await completeTrade(otherUser, created.productId);
      return created;
    });

    await test.step('구매자의 구매내역에는 잡히지만 판매내역에는 잡히지 않는다', async () => {
      const purchases = await unwrap<{ productId: number }[]>(
        await user.api.get('/api/members/me/trades/purchases'),
        200,
      );
      expect(purchases.map((p) => p.productId)).toContain(product.productId);

      const sales = await unwrap<{ productId: number }[]>(await user.api.get('/api/members/me/trades/sales'), 200);
      expect(sales.map((s) => s.productId)).not.toContain(product.productId);
    });

    await test.step('판매자의 판매내역에는 잡히지만 구매내역에는 잡히지 않는다', async () => {
      const sales = await unwrap<{ productId: number }[]>(
        await otherUser.api.get('/api/members/me/trades/sales'),
        200,
      );
      expect(sales.map((s) => s.productId)).toContain(product.productId);

      const purchases = await unwrap<{ productId: number }[]>(
        await otherUser.api.get('/api/members/me/trades/purchases'),
        200,
      );
      expect(purchases.map((p) => p.productId)).not.toContain(product.productId);
    });
  });
});
