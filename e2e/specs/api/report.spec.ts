import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import type { AuthedClient } from '../../fixtures/test';
import { expectError, unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';

/**
 * 신고 — 중복 접수 방지 · 취소 권한
 *
 * 왜 e2e 인가: 상태전이 축(이미 신고한 대상 재신고 금지) + 권한 축(본인 신고만 취소 가능).
 * 둘 다 여러 회원의 신원이 얽혀야 재현되는 시나리오라 단위 테스트로는 못 잡는다.
 */

/** 지역 계층(시·도 → 구 → 동)을 한 단계씩 내려가 상품 등록에 필요한 동 레벨 지역 코드를 하나 얻는다. */
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

/** 신고 대상으로 쓸 상품 하나를 만든다. 신고자 본인의 상품이면 안 되므로 항상 판매자 클라이언트로 호출한다. */
async function createProduct(seller: AuthedClient): Promise<{ productId: number; title: string }> {
  const [categories, regionCode] = await Promise.all([
    unwrap<{ id: number }[]>(await seller.api.get('/api/categories'), 200),
    pickDongRegionCode(seller.api),
  ]);
  const title = uniqueTitle('e2e-report');
  const res = await seller.api.post('/api/products', {
    data: {
      categoryId: categories[0]!.id,
      title,
      description: 'e2e 신고 테스트용 상품',
      price: 10000,
      regionCode,
      imageUrls: ['https://example.com/e2e.jpg'],
      thumbnailIndex: 0,
    },
  });
  const created = await unwrap<{ productId: number }>(res, 201);
  return { productId: created.productId, title };
}

test.describe('신고 — 중복 접수 방지 · 취소 권한', () => {
  test('같은 상품을 이미 신고했다면 다시 신고할 수 없다', async ({ user, otherUser }) => {
    const product = await test.step('준비: 다른 사용자가 판매하는 상품을 만든다', async () => createProduct(otherUser));

    await test.step('처음 신고하면 접수된다', async () => {
      const res = await user.api.post(`/api/products/${product.productId}/reports`, {
        data: { reason: 'FRAUD_SUSPECTED', content: 'e2e: 사기가 의심됩니다.' },
      });
      const report = await unwrap<{ reportId: number; status: string }>(res, 201);
      expect(report.status).toBe('RECEIVED');
    });

    await test.step('같은 신고자가 같은 상품을 다시 신고하면 거절된다', async () => {
      const res = await user.api.post(`/api/products/${product.productId}/reports`, {
        data: { reason: 'FAKE_ITEM', content: 'e2e: 두 번째 신고 시도입니다.' },
      });
      await expectError(res, 409, 'DUPLICATE_REPORT');
    });

    await test.step('내 신고 목록에는 한 건만 남아 있다', async () => {
      const list = await unwrap<{ targetId: number | null }[]>(
        await user.api.get('/api/members/me/reports'),
        200,
      );
      const mine = list.filter((r) => r.targetId === product.productId);
      expect(mine.length).toBe(1);
    });
  });

  test('본인이 접수한 신고만 취소할 수 있다', async ({ user, otherUser }) => {
    const product = await test.step('준비: 다른 사용자가 판매하는 상품을 만든다', async () => createProduct(otherUser));

    const reportId = await test.step('신고를 접수한다', async () => {
      const res = await user.api.post(`/api/products/${product.productId}/reports`, {
        data: { reason: 'PROHIBITED_ITEM', content: 'e2e: 판매 금지 품목으로 보입니다.' },
      });
      const report = await unwrap<{ reportId: number }>(res, 201);
      return report.reportId;
    });

    await test.step('신고자가 아닌 사용자는 취소할 수 없다', async () => {
      const res = await otherUser.api.delete(`/api/members/me/reports/${reportId}`);
      await expectError(res, 403, 'REPORT_OWNER_ONLY');
    });

    await test.step('신고자 본인은 취소할 수 있고, 목록에서 사라진다', async () => {
      const res = await user.api.delete(`/api/members/me/reports/${reportId}`);
      await unwrap(res, 200);

      const list = await unwrap<{ reportId: number }[]>(await user.api.get('/api/members/me/reports'), 200);
      expect(list.map((r) => r.reportId)).not.toContain(reportId);
    });
  });
});
