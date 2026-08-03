import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import type { AuthedClient } from '../../fixtures/test';
import { unwrap } from '../../support/api';

/**
 * 매너 후기 — 채팅방에서 거래완료 후 후기를 남기면 매너온도에 반영된다
 *
 * 거래완료·채팅방 연결까지의 준비는 API로 끝내고, 실제로 검증할 화면 여정(채팅방에서
 * "후기 남기기" → 별점 등록 → 매너온도 반영)만 브라우저로 조작한다.
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

async function createProduct(seller: AuthedClient): Promise<{ productId: number }> {
  const [categories, regionCode] = await Promise.all([
    unwrap<{ id: number }[]>(await seller.api.get('/api/categories'), 200),
    pickDongRegionCode(seller.api),
  ]);
  const res = await seller.api.post('/api/products', {
    data: {
      categoryId: categories[0]!.id,
      title: `e2e-ui-manner-${Date.now()}`,
      description: 'e2e UI 매너 후기 테스트용 상품',
      price: 20000,
      regionCode,
      imageUrls: ['https://example.com/e2e.jpg'],
      thumbnailIndex: 0,
    },
  });
  const created = await unwrap<{ productId: number }>(res, 201);
  return { productId: created.productId };
}

test.describe('매너 후기 — 채팅방에서 남기면 매너온도에 반영된다', () => {
  test('구매자가 채팅방에서 후기를 남기면 판매자의 매너온도가 오른다', async ({ page, user, otherUser }) => {
    // otherUser = 판매자, user = 구매자(로그인해서 후기를 남길 계정).
    const roomId = await test.step('준비: 판매자가 상품을 등록하고, 구매자가 채팅방을 연 뒤 거래가 완료된다', async () => {
      const product = await createProduct(otherUser);
      const room = await unwrap<{ roomId: number }>(
        await user.api.post('/api/chat-rooms', { data: { productId: product.productId } }),
        200,
      );
      await unwrap(
        await otherUser.api.patch(`/api/products/${product.productId}/status`, {
          data: { tradeStatus: 'COMPLETED' },
        }),
        200,
      );
      return room.roomId;
    });

    const beforeScore = await test.step('거래 전 판매자의 매너온도를 확인해둔다', async () => {
      const score = await unwrap<{ score: number }>(
        await user.api.get(`/api/members/${otherUser.memberId}/manner-score`),
        200,
      );
      return score.score;
    });

    await test.step('구매자로 로그인한다', async () => {
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

    await test.step('채팅방에서 별점 5점 후기를 남긴다', async () => {
      // 채팅방 페이지 자체도 진입 시 API 요청을 여러 개 동시에 쏘므로(방 목록·메시지), networkidle
      // 대신(WebSocket이 붙어 있어 끝내 안정화되지 않는다) 실제 데이터 로드가 끝나는 시점을 정확히 기다린다.
      const roomsLoaded = page.waitForResponse(
        (res) =>
          new URL(res.url()).pathname === '/api/chat-rooms' &&
          res.request().method() === 'GET' &&
          res.status() === 200,
      );
      await page.goto(`/chat/${roomId}`);
      await roomsLoaded;
      await page.getByRole('button', { name: '후기 남기기' }).click();
      await page.getByRole('button', { name: '5점' }).click();
      await page.getByRole('button', { name: '등록하기' }).click();
      await expect(page.getByText('소중한 후기 감사해요')).toBeVisible();
    });

    await test.step('판매자의 매너온도가 실제로 올랐다', async () => {
      const after = await unwrap<{ score: number }>(
        await user.api.get(`/api/members/${otherUser.memberId}/manner-score`),
        200,
      );
      expect(after.score, '후기 등록 뒤에도 매너온도가 오르지 않았다').toBeGreaterThan(beforeScore);
    });
  });
});
