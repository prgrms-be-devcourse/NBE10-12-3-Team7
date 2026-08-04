import { test, expect } from '../../fixtures/test';
import { unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';
import type { APIRequestContext } from '@playwright/test';

/**
 * 지역 도메인 e2e.
 *
 * 지역 API 단독으로는 e2e 축(돈·권한·상태전이·동시성)에 걸리는 게 없다. 목록 조회 하나뿐이고
 * 권한도 상태도 없다. 그래서 여기서 검증하는 것은 **지역 코드가 상품 도메인을 관통할 때만
 * 드러나는 계약**이다.
 *
 *   1. 지역 API 가 주는 code 형식과 ProductSpecification.toRegionCodePrefix 의 자릿수 규칙이 맞는가
 *   2. 계층 깊이가 지역마다 다른데(세종 2단 / 나머지 3단) 양쪽 다 상품 등록이 되는가
 *
 * 어긋나면 예외가 아니라 **빈 목록**이 나온다. 조용히 실패하는 종류라 관통 테스트가 아니면 못 잡는다.
 * 지역 조회 자체(정렬·빈 목록·부모 필터)는 RegionRepositoryTest·RegionControllerTest 가 덮는다.
 */

interface RegionSummary {
  code: string;
  level: number;
  parentCode: string | null;
  displayName: string;
}

interface ProductSummary {
  productId: number;
  regionCode: string;
}

interface ProductPage {
  items: ProductSummary[];
  nextCursor: number | null;
  hasNext: boolean;
}

/** 시·도 → (시·군·구) → 동. 세종처럼 2단인 지역은 sigungu 가 null 이다. */
interface Hierarchy {
  sido: RegionSummary;
  sigungu: RegionSummary | null;
  dong: RegionSummary;
}

// ── 지역 계층 해석 ────────────────────────────────────────────────────────────
// 시더가 채우는 읽기 전용 기준데이터라 워커 안에서 한 번만 구하면 된다.

let sidoCache: Promise<RegionSummary[]> | null = null;
let threeLevelCache: Promise<Hierarchy> | null = null;
let twoLevelCache: Promise<Hierarchy> | null = null;

async function regions(api: APIRequestContext, parentCode?: string): Promise<RegionSummary[]> {
  const path = parentCode ? `/api/regions?parentCode=${parentCode}` : '/api/regions';
  return await unwrap<RegionSummary[]>(await api.get(path), 200);
}

function sidoList(api: APIRequestContext): Promise<RegionSummary[]> {
  return (sidoCache ??= regions(api));
}

/** 시·도 → 시·군·구 → 동 3단 계층을 하나 찾는다(서울·경기 등 대부분). */
function threeLevel(api: APIRequestContext): Promise<Hierarchy> {
  return (threeLevelCache ??= (async () => {
    for (const sido of await sidoList(api)) {
      for (const sigungu of await regions(api, sido.code)) {
        if (sigungu.level !== 2) continue;
        const dong = (await regions(api, sigungu.code)).find((r) => r.level === 3);
        if (dong) return { sido, sigungu, dong };
      }
    }
    throw new Error('3단 계층 지역을 찾지 못했다 — RegionSeeder 확인 (npm run smoke)');
  })());
}

/** 시·도 바로 아래가 동인 2단 계층을 찾는다(세종). 없으면 실패시킨다. */
function twoLevel(api: APIRequestContext): Promise<Hierarchy> {
  return (twoLevelCache ??= (async () => {
    for (const sido of await sidoList(api)) {
      const dong = (await regions(api, sido.code)).find((r) => r.level === 3);
      if (dong) return { sido, sigungu: null, dong };
    }
    throw new Error('2단 계층 지역(세종)을 찾지 못했다 — RegionSeeder 확인');
  })());
}

// ── 상품 등록·조회 ────────────────────────────────────────────────────────────

async function createProductInRegion(
  client: APIRequestContext,
  regionCode: string,
  title: string,
): Promise<number> {
  const categories = await unwrap<{ id: number }[]>(await client.get('/api/categories'), 200);
  const category = categories[0];
  if (!category) throw new Error('카테고리가 비어 있다 — CategorySeeder 확인');

  const res = await client.post('/api/products', {
    data: {
      categoryId: category.id,
      title,
      description: 'e2e 지역 관통 검증용 상품입니다.',
      price: 25000,
      regionCode,
      imageUrls: ['/api/products/images/e2e-placeholder.png'],
      thumbnailIndex: 0,
    },
  });
  const created = await unwrap<{ productId: number }>(res, 201);
  return created.productId;
}

/** 지역 필터를 걸어 목록을 커서로 훑는다. 전역 개수가 아니라 특정 id 포함 여부만 본다(규칙 2). */
async function filteredListContains(
  client: APIRequestContext,
  regionCode: string,
  productId: number,
): Promise<boolean> {
  let cursor: number | null = null;
  for (let page = 0; page < 20; page++) {
    const base = `/api/products?size=100&regionCodes=${regionCode}`;
    const path: string = cursor ? `${base}&cursor=${cursor}` : base;
    const res: ProductPage = await unwrap<ProductPage>(await client.get(path), 200);
    if (res.items.some((p) => p.productId === productId)) return true;
    if (!res.hasNext || res.nextCursor === null) return false;
    cursor = res.nextCursor;
  }
  throw new Error('목록 페이지를 20장 넘게 넘겼다 — 커서가 끝나지 않는다');
}

// ─────────────────────────────────────────────────────────────────────────────

test.describe('지역 — 코드 체계가 상품 필터까지 관통한다', () => {
  test('시·도 → 시·군·구 → 동 드릴다운으로 등록한 상품이 상위 지역 필터에 잡힌다', async ({
    user,
  }) => {
    const { sido, sigungu, dong } = await test.step('3단 드릴다운', async () => {
      const found = await threeLevel(user.api);
      // 프론트 지역 선택 UI 가 그대로 타는 경로다. 각 단계의 부모 관계까지 못 박는다.
      expect(found.sido.level).toBe(1);
      expect(found.sigungu?.level).toBe(2);
      expect(found.sigungu?.parentCode).toBe(found.sido.code);
      expect(found.dong.level).toBe(3);
      expect(found.dong.parentCode).toBe(found.sigungu?.code);
      return found;
    });

    const title = uniqueTitle('e2e-지역3단');
    const productId = await test.step(`${dong.displayName}(동) 코드로 상품을 등록한다`, async () =>
      await createProductInRegion(user.api, dong.code, title));

    await test.step(`동 코드(${dong.code}) 필터에 잡힌다`, async () => {
      expect(await filteredListContains(user.api, dong.code, productId)).toBe(true);
    });

    await test.step(`시·군·구 코드(${sigungu?.code}) 필터에 잡힌다 — prefix 5자리`, async () => {
      // toRegionCodePrefix: "...00000" 으로 끝나면 앞 5자리로 자른다.
      expect(await filteredListContains(user.api, sigungu!.code, productId)).toBe(true);
    });

    await test.step(`시·도 코드(${sido.code}) 필터에 잡힌다 — prefix 2자리`, async () => {
      // toRegionCodePrefix: "...00000000" 으로 끝나면 앞 2자리로 자른다.
      expect(await filteredListContains(user.api, sido.code, productId)).toBe(true);
    });

    await test.step('다른 시·도 필터에는 잡히지 않는다', async () => {
      // 음성 확인이 없으면 "필터가 항상 전부 반환"해도 위 단언이 전부 통과한다.
      const other = (await sidoList(user.api)).find((r) => r.code !== sido.code);
      expect(other, '비교할 다른 시·도가 없다').toBeTruthy();
      expect(
        await filteredListContains(user.api, other!.code, productId),
        `${other!.displayName} 필터에 ${sido.displayName} 상품이 잡혔다`,
      ).toBe(false);
    });
  });

  test('시·도 바로 아래가 동인 2단 계층 지역에서도 상품을 등록하고 필터할 수 있다', async ({
    user,
  }) => {
    const { sido, dong } = await test.step('2단 계층 확보', async () => {
      const found = await twoLevel(user.api);
      // 세종은 시·군·구가 없다. 3단을 가정한 코드는 여기서 빈 배열을 받고 무너진다.
      expect(found.sigungu).toBeNull();
      expect(found.dong.level, '시·도 바로 아래인데 level 3 이 아니다').toBe(3);
      expect(found.dong.parentCode).toBe(found.sido.code);
      return found;
    });

    const title = uniqueTitle('e2e-지역2단');
    const productId = await test.step(
      `${sido.displayName} ${dong.displayName} 코드로 상품을 등록한다`,
      async () => {
        // ProductService.getRequiredDongRegion 이 level == 3 을 강제한다.
        // 2단 지역의 하위가 level 2 로 시딩됐다면 이 지역에서는 상품 등록이 아예 불가능해진다.
        return await createProductInRegion(user.api, dong.code, title);
      },
    );

    await test.step(`시·도 코드(${sido.code}) 필터에 잡힌다`, async () => {
      // 세종 코드는 "3611000000" 이라 8자리 0 으로 끝나지 않는다 — 2자리가 아니라 5자리로 잘린다.
      // 그래도 하위 동이 같은 5자리로 시작해서 맞아떨어지는지가 이 단언의 요점이다.
      expect(await filteredListContains(user.api, sido.code, productId)).toBe(true);
    });
  });
});
