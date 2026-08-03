import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { expectError, unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';

/**
 * 관심 상품 — 등록/취소 생명주기와 규칙 (권건우)
 *
 * 왜 e2e 인가: 권한·규칙 축. "본인 상품은 관심 등록 불가", "남이 등록한 관심은 못 건드림",
 * "중복 등록 거부"는 시큐리티 필터 + 서비스 규칙 + DB 유니크 제약에 걸쳐 있어 단위 테스트가
 * 건너뛰는 경로다. 코틀린 전환이 이 경로를 옮겼으므로 동작 불변을 여기서 증명한다.
 *
 * 소유권 방향에 주의: 관심 등록 대상 상품은 반드시 *상대방*(otherUser) 소유여야 한다.
 * 본인 상품이면 happy path 가 CANNOT_FAVORITE_OWN_PRODUCT(400) 로 떨어진다.
 */

/**
 * 상품 등록이 요구하는 "동(洞)" 지역코드를 시드된 지역 트리에서 런타임에 뽑는다.
 * 지역은 3단계 계층(level1 시/도 → level2 시/군/구 → level3 동)이고, 상품 등록은 level 3 만 받는다
 * (ProductService.getRequiredDongRegion). /api/regions 는 최상위만 주므로 parentCode 로 두 번 내려간다.
 * 코드값을 하드코딩하지 않는 이유는 규칙 ①(고정 ID 금지) — 시드가 바뀌어도 안 깨지게 매번 트리에서 읽는다.
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

/**
 * 상품 하나를 만든다. favorite 준비물일 뿐 검증 대상이 아니라 이 파일 안 로컬 함수로 둔다.
 * categoryId·regionCode 는 하드코딩하지 않고 시드된 마스터 데이터에서 런타임에 읽는다(읽기만, 절대 수정 안 함).
 */
async function createProduct(api: APIRequestContext): Promise<number> {
  const categories = await unwrap<{ id: number }[]>(await api.get('/api/categories'), 200);
  const regionCode = await pickDongRegionCode(api);

  const created = await unwrap<{ productId: number }>(
    await api.post('/api/products', {
      data: {
        categoryId: categories[0].id,
        title: uniqueTitle('상품'),
        description: 'e2e 관심상품 테스트용',
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

interface MyFavorite {
  favoriteId: number;
  product: { productId: number };
}

test.describe('관심 상품 — 등록/취소 생명주기와 규칙', () => {
  test('관심 등록하면 내 목록에 뜨고, 취소하면 사라진다', async ({ user, otherUser }) => {
    // 관심 등록 대상은 상대방 상품이어야 한다(본인 상품은 규칙상 등록 불가).
    const productId = await createProduct(otherUser.api);

    await test.step('관심 등록하면 201 이다', async () => {
      const res = await user.api.post(`/api/products/${productId}/favorites`);
      await unwrap(res, 201);
    });

    await test.step('내 관심 목록에 방금 등록한 상품이 포함된다', async () => {
      // 전역 개수(toBe)는 병렬 실행에서 깨지므로, 내 상품이 들어있는지만(toContain) 본다.
      const list = await unwrap<MyFavorite[]>(await user.api.get('/api/members/me/favorites'), 200);
      expect(list.map((f) => f.product.productId)).toContain(productId);
    });

    await test.step('같은 상품을 또 등록하면 FAVORITE_ALREADY_EXISTS(409) 로 거부된다', async () => {
      const res = await user.api.post(`/api/products/${productId}/favorites`);
      await expectError(res, 409, 'FAVORITE_ALREADY_EXISTS');
    });

    await test.step('취소하면 200 이고, 내 목록에서 빠진다', async () => {
      await unwrap(await user.api.delete(`/api/products/${productId}/favorites`), 200);

      const list = await unwrap<MyFavorite[]>(await user.api.get('/api/members/me/favorites'), 200);
      expect(list.map((f) => f.product.productId)).not.toContain(productId);
    });

    await test.step('등록되지 않은 관심을 다시 취소하면 FAVORITE_NOT_FOUND(404)', async () => {
      const res = await user.api.delete(`/api/products/${productId}/favorites`);
      await expectError(res, 404, 'FAVORITE_NOT_FOUND');
    });
  });

  test('본인이 등록한 상품은 관심 등록할 수 없다', async ({ user }) => {
    const myProductId = await createProduct(user.api);

    const res = await user.api.post(`/api/products/${myProductId}/favorites`);
    await expectError(res, 400, 'CANNOT_FAVORITE_OWN_PRODUCT');
  });

  test('비로그인 사용자는 관심 등록할 수 없다', async ({ api, otherUser }) => {
    const productId = await createProduct(otherUser.api);

    const res = await api.post(`/api/products/${productId}/favorites`);
    expect(res.status()).toBe(401);
  });

  test('존재하지 않는 상품에 관심 등록하면 PRODUCT_NOT_FOUND(404)', async ({ user }) => {
    const res = await user.api.post('/api/products/99999999/favorites');
    await expectError(res, 404, 'PRODUCT_NOT_FOUND');
  });
});

// ── PR 전 체크리스트 ──────────────────────────────────────────────────────────
// [x] 고정 ID/이메일/시드에 의존하지 않는가 (상품·회원을 매번 새로 만든다)
// [x] 전역 개수를 단언하지 않는가 (toContain 으로 내 상품만 확인)
// [x] 에러 케이스에서 ErrorCode 까지 단언했는가
// [x] npx playwright test specs/api/favorite.spec.ts --repeat-each=3 통과 (RATE_LIMIT_CAPACITY 상향 반영본에서 확인)
