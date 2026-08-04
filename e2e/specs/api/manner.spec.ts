import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import type { AuthedClient } from '../../fixtures/test';
import { expectError, unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';

/**
 * 매너 후기 — 거래 완료·실거래 참여자만 별점을 남길 수 있다
 *
 * 왜 e2e 인가: 상태전이 축(거래완료 전에는 후기 금지) + 권한 축(그 거래의 실제 구매자만 가능).
 * 상품(product) · 채팅방(chat) · 매너온도(manner) 세 도메인에 걸쳐 있어 단위 테스트로는
 * "실제로 이어 붙였을 때"의 계약을 증명하지 못한다.
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

/** 판매자가 상품을 등록한다. */
async function createProduct(seller: AuthedClient): Promise<{ productId: number }> {
  const [categories, regionCode] = await Promise.all([
    unwrap<{ id: number }[]>(await seller.api.get('/api/categories'), 200),
    pickDongRegionCode(seller.api),
  ]);
  const res = await seller.api.post('/api/products', {
    data: {
      categoryId: categories[0]!.id,
      title: uniqueTitle('e2e-manner'),
      description: 'e2e 매너 후기 테스트용 상품',
      price: 20000,
      regionCode,
      imageUrls: ['https://example.com/e2e.jpg'],
      thumbnailIndex: 0,
    },
  });
  const created = await unwrap<{ productId: number }>(res, 201);
  return { productId: created.productId };
}

/** 구매자가 채팅방을 열어(get-or-create) 그 상품의 "실거래 참여자"가 된다. */
async function openChatRoom(buyer: AuthedClient, productId: number): Promise<void> {
  const res = await buyer.api.post('/api/chat-rooms', { data: { productId } });
  await unwrap(res, 200);
}

/** 판매자가 거래완료로 전환한다. */
async function completeTrade(seller: AuthedClient, productId: number): Promise<void> {
  const res = await seller.api.patch(`/api/products/${productId}/status`, {
    data: { tradeStatus: 'COMPLETED' },
  });
  await unwrap(res, 200);
}

test.describe('매너 후기 — 거래완료·실거래 참여자 검증', () => {
  test('완료된 거래의 구매자가 별점을 남기면 매너온도가 오르고, 같은 거래에 두 번은 남길 수 없다', async ({
    user,
    otherUser,
  }) => {
    // otherUser = 판매자, user = 구매자.
    const product = await test.step('준비: 판매자가 상품을 등록하고, 구매자가 채팅방을 연 뒤 거래가 완료된다', async () => {
      const created = await createProduct(otherUser);
      await openChatRoom(user, created.productId);
      await completeTrade(otherUser, created.productId);
      return created;
    });

    const beforeScore = await test.step('거래 전 판매자의 매너온도를 확인해둔다', async () => {
      const score = await unwrap<{ score: number }>(
        await user.api.get(`/api/members/${otherUser.memberId}/manner-score`),
        200,
      );
      return score.score;
    });

    await test.step('구매자가 5점 후기를 남기면 접수되고, 판매자의 매너온도가 오른다', async () => {
      const res = await user.api.post('/api/manner/ratings', {
        data: { productId: product.productId, score: 5 },
      });
      const rating = await unwrap<{ productId: number; score: number }>(res, 201);
      expect(rating.productId).toBe(product.productId);
      expect(rating.score).toBe(5);

      const after = await unwrap<{ score: number }>(
        await user.api.get(`/api/members/${otherUser.memberId}/manner-score`),
        200,
      );
      expect(after.score, '5점 후기 뒤에도 매너온도가 오르지 않았다').toBeGreaterThan(beforeScore);
    });

    await test.step('같은 구매자가 같은 거래에 또 후기를 남기면 거절된다', async () => {
      const res = await user.api.post('/api/manner/ratings', {
        data: { productId: product.productId, score: 3 },
      });
      await expectError(res, 409, 'MANNER_RATING_ALREADY_EXISTS');
    });
  });

  test('거래완료 전이거나 실거래 참여자(구매자)가 아니면 후기를 남길 수 없다', async ({ user, otherUser }) => {
    // otherUser = 판매자, user = 구매자.
    const product = await test.step('준비: 판매자가 상품을 등록하고, 구매자가 채팅방을 연다(아직 거래완료 전)', async () => {
      const created = await createProduct(otherUser);
      await openChatRoom(user, created.productId);
      return created;
    });

    await test.step('거래완료 전에는 실거래 참여자라도 후기를 남길 수 없다', async () => {
      const res = await user.api.post('/api/manner/ratings', {
        data: { productId: product.productId, score: 5 },
      });
      await expectError(res, 400, 'MANNER_RATING_TRADE_NOT_COMPLETED');
    });

    await test.step('거래가 완료된 뒤, 그 거래의 구매자가 아닌 사람(판매자 본인)은 후기를 남길 수 없다', async () => {
      await completeTrade(otherUser, product.productId);

      const res = await otherUser.api.post('/api/manner/ratings', {
        data: { productId: product.productId, score: 5 },
      });
      await expectError(res, 403, 'MANNER_RATING_NOT_A_PARTICIPANT');
    });
  });
});
