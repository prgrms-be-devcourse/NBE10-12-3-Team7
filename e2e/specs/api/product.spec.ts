import { test, expect } from '../../fixtures/test';
import { expectError, unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';
import type { APIRequestContext } from '@playwright/test';

/**
 * 상품 도메인 e2e.
 *
 * 채택 기준은 e2e.md 의 네 축(돈·권한·상태전이·동시성)이다. 여기 있는 스펙은 전부
 * **권한** 또는 **상태 전이** 에 걸린다. 커서 페이징·검색 조건 조합처럼 한 요청 안에서
 * 끝나는 것은 ProductControllerTest(슬라이스)가 이미 덮고 있어 여기 두지 않는다.
 *
 * 동시성(조회수 경합)을 넣지 않은 이유: `retries: 0` 인데 조회수 유실은 매번 재현되지 않아
 * 구조적으로 flaky 하다. 그 경로는 loadtest/product-detail-hotrow.js 가 본다.
 */

interface ProductResponse {
  productId: number;
  categoryId: number;
  title: string;
  price: number;
  tradeStatus: 'ON_SALE' | 'RESERVED' | 'COMPLETED';
  regionCode: string;
  viewCount: number;
  thumbnailUrl: string | null;
  imageUrls: string[];
}

interface ProductSummary {
  productId: number;
  title: string;
  tradeStatus: string;
}

interface ProductPage {
  items: ProductSummary[];
  nextCursor: number | null;
  hasNext: boolean;
}

interface RegionSummary {
  code: string;
  level: number;
}

/** 1x1 PNG. ProductImageStorageService 가 contentType 을 검사하므로 실제 이미지 바이트여야 한다. */
const TINY_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);

// ── 마스터 데이터 조회 ────────────────────────────────────────────────────────
// 지역·카테고리는 시더가 채우는 읽기 전용 기준데이터라 워커 안에서 한 번만 구하면 된다.
// (테스트가 만드는 데이터가 아니므로 캐시해도 "고정 데이터 의존" 규칙에 걸리지 않는다.)

let dongCodeCache: Promise<string> | null = null;
let categoryIdCache: Promise<number> | null = null;

async function regions(api: APIRequestContext, parentCode?: string): Promise<RegionSummary[]> {
  const path = parentCode ? `/api/regions?parentCode=${parentCode}` : '/api/regions';
  return await unwrap<RegionSummary[]>(await api.get(path), 200);
}

/**
 * 상품 등록에 쓸 수 있는 동(level 3) 코드를 찾는다.
 *
 * 단순히 `regions()[0]` 을 세 번 타면 안 된다. 세종은 시·도 바로 아래에 읍·면·동이 오는
 * 2단 계층이라(RegionControllerTest 참고) 세 번째 호출이 빈 배열이 된다.
 * 상품 등록은 ProductService.getRequiredDongRegion 이 level == 3 을 강제하므로 level 로 찾는다.
 */
async function resolveDongCode(api: APIRequestContext): Promise<string> {
  for (const sido of await regions(api)) {
    for (const child of await regions(api, sido.code)) {
      if (child.level === 3) return child.code; // 세종형 2단 계층
      const dong = (await regions(api, child.code)).find((r) => r.level === 3);
      if (dong) return dong.code;
    }
  }
  throw new Error('level 3 지역을 찾지 못했다 — RegionSeeder 확인 (npm run smoke)');
}

function dongCode(api: APIRequestContext): Promise<string> {
  return (dongCodeCache ??= resolveDongCode(api));
}

function categoryId(api: APIRequestContext): Promise<number> {
  return (categoryIdCache ??= (async () => {
    const categories = await unwrap<{ id: number }[]>(await api.get('/api/categories'), 200);
    const first = categories[0];
    if (!first) throw new Error('카테고리가 비어 있다 — CategorySeeder 확인 (npm run smoke)');
    return first.id;
  })());
}

// ── 상품 생성 헬퍼 ────────────────────────────────────────────────────────────

interface CreateOverrides {
  title?: string;
  price?: number;
  imageUrls?: string[];
  thumbnailIndex?: number;
}

async function createProduct(
  client: APIRequestContext,
  overrides: CreateOverrides = {},
): Promise<ProductResponse> {
  const res = await client.post('/api/products', {
    data: {
      categoryId: await categoryId(client),
      title: overrides.title ?? uniqueTitle('e2e-상품'),
      description: 'e2e 검증용 상품입니다.',
      price: overrides.price ?? 30000,
      regionCode: await dongCode(client),
      imageUrls: overrides.imageUrls ?? ['/api/products/images/e2e-placeholder.png'],
      thumbnailIndex: overrides.thumbnailIndex ?? 0,
    },
  });
  return await unwrap<ProductResponse>(res, 201);
}

async function changeStatus(
  client: APIRequestContext,
  productId: number,
  tradeStatus: string,
): Promise<ProductResponse> {
  const res = await client.patch(`/api/products/${productId}/status`, { data: { tradeStatus } });
  return await unwrap<ProductResponse>(res, 200);
}

/** 목록 전체를 커서로 훑어 특정 상품이 노출되는지 본다(전역 개수는 단언하지 않는다 — 규칙 2). */
async function listContains(client: APIRequestContext, productId: number): Promise<boolean> {
  let cursor: number | null = null;
  for (let page = 0; page < 20; page++) {
    const path: string = cursor ? `/api/products?size=100&cursor=${cursor}` : '/api/products?size=100';
    const res: ProductPage = await unwrap<ProductPage>(await client.get(path), 200);
    if (res.items.some((p) => p.productId === productId)) return true;
    if (!res.hasNext || res.nextCursor === null) return false;
    cursor = res.nextCursor;
  }
  throw new Error('목록 페이지를 20장 넘게 넘겼다 — 커서가 끝나지 않는다');
}

// ─────────────────────────────────────────────────────────────────────────────

test.describe('상품 — 거래 상태 전이', () => {
  test('거래완료하면 목록·검색·상세에서 빠지고 내 상품에는 남는다', async ({ user }) => {
    const title = uniqueTitle('e2e-완료전이');
    const product = await test.step('상품을 등록한다', async () =>
      await createProduct(user.api, { title }));

    await test.step('판매중 상품은 목록·검색·상세에 모두 노출된다', async () => {
      expect(await listContains(user.api, product.productId), '목록에 없다').toBe(true);

      const found = await unwrap<ProductSummary[]>(
        await user.api.get(`/api/products/search?keyword=${encodeURIComponent(title)}`),
        200,
      );
      expect(found.map((p) => p.productId)).toContain(product.productId);

      const detail = await unwrap<ProductResponse>(
        await user.api.get(`/api/products/${product.productId}`),
        200,
      );
      expect(detail.tradeStatus).toBe('ON_SALE');
    });

    await test.step('예약중으로 바꾸면 아직 노출된다', async () => {
      const reserved = await changeStatus(user.api, product.productId, 'RESERVED');
      expect(reserved.tradeStatus).toBe('RESERVED');
      expect(await listContains(user.api, product.productId), '예약중인데 목록에서 사라졌다').toBe(true);
    });

    await test.step('거래완료로 바꾼다', async () => {
      const completed = await changeStatus(user.api, product.productId, 'COMPLETED');
      expect(completed.tradeStatus).toBe('COMPLETED');
    });

    await test.step('완료 상품은 상세 조회가 막힌다', async () => {
      // ProductService.getProduct 는 완료 상품을 "없는 것"으로 취급한다(HIDDEN 과 다른 코드).
      await expectError(
        await user.api.get(`/api/products/${product.productId}`),
        404,
        'PRODUCT_NOT_FOUND',
      );
    });

    await test.step('완료 상품은 목록과 검색에서 동시에 빠진다', async () => {
      // ProductSpecification.notCompleted() 가 목록·검색·카테고리 세 경로에 함께 걸려 있다.
      expect(await listContains(user.api, product.productId), '완료인데 목록에 남아 있다').toBe(false);

      const found = await unwrap<ProductSummary[]>(
        await user.api.get(`/api/products/search?keyword=${encodeURIComponent(title)}`),
        200,
      );
      expect(found.map((p) => p.productId)).not.toContain(product.productId);
    });

    await test.step('내 상품 목록에는 남는다', async () => {
      const mine = await unwrap<ProductSummary[]>(await user.api.get('/api/products/me'), 200);
      expect(mine.map((p) => p.productId), '판매 이력이 사라졌다').toContain(product.productId);
    });
  });

  test('삭제하면 상세·수정·상태변경이 모두 막힌다', async ({ user }) => {
    const product = await createProduct(user.api);

    const deleted = await user.api.delete(`/api/products/${product.productId}`);
    expect(deleted.status()).toBe(200);

    await test.step('상세 조회가 막힌다', async () => {
      await expectError(
        await user.api.get(`/api/products/${product.productId}`),
        404,
        'DELETED_PRODUCT',
      );
    });

    await test.step('수정이 막힌다', async () => {
      const res = await user.api.patch(`/api/products/${product.productId}`, {
        data: {
          categoryId: await categoryId(user.api),
          title: uniqueTitle('e2e-수정시도'),
          description: '삭제된 상품 수정 시도',
          price: 40000,
          regionCode: await dongCode(user.api),
          imageUrls: ['/api/products/images/e2e-placeholder.png'],
          thumbnailIndex: 0,
        },
      });
      await expectError(res, 404, 'DELETED_PRODUCT');
    });

    await test.step('상태 변경이 막힌다', async () => {
      const res = await user.api.patch(`/api/products/${product.productId}/status`, {
        data: { tradeStatus: 'RESERVED' },
      });
      await expectError(res, 404, 'DELETED_PRODUCT');
    });

    await test.step('목록에서도 빠진다', async () => {
      expect(await listContains(user.api, product.productId)).toBe(false);
    });
  });

  test('거래완료 상품은 수정할 수 없다', async ({ user }) => {
    const product = await createProduct(user.api);
    await changeStatus(user.api, product.productId, 'COMPLETED');

    const res = await user.api.patch(`/api/products/${product.productId}`, {
      data: {
        categoryId: await categoryId(user.api),
        title: uniqueTitle('e2e-완료수정'),
        description: '완료 후 수정 시도',
        price: 50000,
        regionCode: await dongCode(user.api),
        imageUrls: ['/api/products/images/e2e-placeholder.png'],
        thumbnailIndex: 0,
      },
    });

    await expectError(res, 400, 'CANNOT_UPDATE_COMPLETED_PRODUCT');
  });

  test('거래완료 상품은 판매중으로 되돌릴 수 없다', async ({ user }) => {
    const product = await createProduct(user.api);
    await changeStatus(user.api, product.productId, 'COMPLETED');

    await expectError(
      await user.api.patch(`/api/products/${product.productId}/status`, {
        data: { tradeStatus: 'ON_SALE' },
      }),
      400,
      'CANNOT_CHANGE_COMPLETED_PRODUCT',
    );

    // 완료 → 완료 재요청은 멱등하게 허용된다(되돌리기만 막는다).
    const again = await changeStatus(user.api, product.productId, 'COMPLETED');
    expect(again.tradeStatus).toBe('COMPLETED');
  });
});

test.describe('상품 — 소유권', () => {
  // 단위 테스트는 시큐리티 필터를 건너뛴다. 실제 JWT 가 붙은 상태의 소유권 판정은 여기서만 검증된다.

  test('남의 상품은 수정할 수 없다', async ({ user, otherUser }) => {
    const mine = await createProduct(user.api);

    const res = await otherUser.api.patch(`/api/products/${mine.productId}`, {
      data: {
        categoryId: await categoryId(otherUser.api),
        title: uniqueTitle('e2e-남이수정'),
        description: '남의 상품 수정 시도',
        price: 10000,
        regionCode: await dongCode(otherUser.api),
        imageUrls: ['/api/products/images/e2e-placeholder.png'],
        thumbnailIndex: 0,
      },
    });

    await expectError(res, 403, 'PRODUCT_OWNER_ONLY');
  });

  test('남의 상품은 삭제할 수 없다', async ({ user, otherUser }) => {
    const mine = await createProduct(user.api);

    await expectError(
      await otherUser.api.delete(`/api/products/${mine.productId}`),
      403,
      'PRODUCT_OWNER_ONLY',
    );

    // 실패한 삭제가 상태를 바꾸지 않았는지까지 확인한다.
    const detail = await unwrap<ProductResponse>(
      await user.api.get(`/api/products/${mine.productId}`),
      200,
    );
    expect(detail.productId).toBe(mine.productId);
  });

  test('남의 상품은 거래 상태를 바꿀 수 없다', async ({ user, otherUser }) => {
    const mine = await createProduct(user.api);

    await expectError(
      await otherUser.api.patch(`/api/products/${mine.productId}/status`, {
        data: { tradeStatus: 'COMPLETED' },
      }),
      403,
      'PRODUCT_OWNER_ONLY',
    );

    const detail = await unwrap<ProductResponse>(
      await user.api.get(`/api/products/${mine.productId}`),
      200,
    );
    expect(detail.tradeStatus, '남이 상태를 바꿨다').toBe('ON_SALE');
  });

  test('비로그인은 등록·수정·삭제·상태변경을 할 수 없다', async ({ user, api }) => {
    const mine = await createProduct(user.api);
    const body = {
      categoryId: await categoryId(api),
      title: uniqueTitle('e2e-비로그인'),
      description: '비로그인 쓰기 시도',
      price: 20000,
      regionCode: await dongCode(api),
      imageUrls: ['/api/products/images/e2e-placeholder.png'],
      thumbnailIndex: 0,
    };

    await expectError(await api.post('/api/products', { data: body }), 401, 'UNAUTHORIZED');
    await expectError(
      await api.patch(`/api/products/${mine.productId}`, { data: body }),
      401,
      'UNAUTHORIZED',
    );
    await expectError(await api.delete(`/api/products/${mine.productId}`), 401, 'UNAUTHORIZED');
    await expectError(
      await api.patch(`/api/products/${mine.productId}/status`, { data: { tradeStatus: 'RESERVED' } }),
      401,
      'UNAUTHORIZED',
    );

    // 읽기는 비로그인도 열려 있어야 한다 — 위 401 이 필터 과잉 적용이 아님을 못 박는다.
    const detail = await unwrap<ProductResponse>(await api.get(`/api/products/${mine.productId}`), 200);
    expect(detail.productId).toBe(mine.productId);
  });
});

test.describe('상품 — 이미지', () => {
  test('업로드한 이미지 URL로 상품을 등록하면 순서와 대표 이미지가 유지된다', async ({ user }) => {
    const uploaded = await test.step('이미지를 업로드한다', async () => {
      const res = await user.api.post('/api/products/images', {
        multipart: { files: { name: 'e2e.png', mimeType: 'image/png', buffer: TINY_PNG } },
      });
      const body = await unwrap<{ imageUrls: string[] }>(res, 201);
      const url = body.imageUrls[0];
      expect(url, '업로드 응답에 URL 이 없다').toBeTruthy();
      expect(url).toContain('/api/products/images/');
      return url as string;
    });

    await test.step('업로드한 URL 로 이미지가 실제로 내려온다', async () => {
      // 저장소 왕복(로컬 디스크 또는 S3 호환)을 확인한다. MockMvc 로는 검증되지 않는 경로다.
      const res = await user.api.get(uploaded);
      expect(res.status()).toBe(200);
      expect(res.headers()['content-type']).toContain('image/png');
    });

    const urls = [uploaded, '/api/products/images/e2e-b.png', '/api/products/images/e2e-c.png'];

    const product = await test.step('3장을 등록하고 마지막을 대표로 지정한다', async () =>
      await createProduct(user.api, { imageUrls: urls, thumbnailIndex: 2 }));

    await test.step('상세에서 순서와 대표 이미지가 그대로다', async () => {
      const detail = await unwrap<ProductResponse>(
        await user.api.get(`/api/products/${product.productId}`),
        200,
      );
      expect(detail.imageUrls, '이미지 순서가 흐트러졌다').toEqual(urls);
      expect(detail.thumbnailUrl, '대표 이미지가 thumbnailIndex 와 다르다').toBe(urls[2]);
    });
  });
});
