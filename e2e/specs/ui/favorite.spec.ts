import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';

/**
 * 관심 상품 — 화면 여정 (권건우) : 로그인 → 상품 상세에서 하트 → 관심 목록에 담긴다
 *
 * 이 UI 스펙의 목적은 로직 재검증이 아니라, develop→main 배포 전에 "사람이 보는 화면에서
 * 관심 담기가 실제로 도는지"를 눈(영상)으로 확인하는 것이다. 권한·중복·에러 같은 계약 검증은
 * 이미 specs/api/favorite.spec.ts 가 담당하므로 여기서 반복하지 않는다 — 대표 여정 하나만 본다(§4).
 *
 * §9 준수:
 *  - 준비(회원·상품)는 API 로 한다. 브라우저로 가입까지 하면 이메일 인증 때문에 느리고 잘 깨진다.
 *  - 검증만 브라우저로 한다.
 *  - 소유권: 관심 담기는 *남의* 상품에만 된다(본인 상품 400). 그래서 상품은 otherUser 가 만들고,
 *    화면에는 user 로 로그인한다. → 상세 화면에 관심 버튼이 보이는 것 자체가 비소유자 뷰 확인이다.
 *
 * 인증 메커닉: Access Token 은 프론트의 모듈 변수에만 있어 하드 내비게이션마다 사라지고,
 * HttpOnly Refresh 쿠키로 재발급돼 복구된다(lib/auth.ts). Playwright 브라우저 컨텍스트가 그 쿠키를
 * 유지하므로 로그인 후 goto 로 페이지를 옮겨도 로그인 상태가 이어진다.
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

/** 상품 하나를 만든다(준비물이라 API 로). 제목은 화면에서 눈으로 확인할 수 있게 돌려준다. */
async function createProduct(api: APIRequestContext): Promise<{ productId: number; title: string }> {
  const categories = await unwrap<{ id: number }[]>(await api.get('/api/categories'), 200);
  const regionCode = await pickDongRegionCode(api);
  const title = uniqueTitle('관심상품');

  const created = await unwrap<{ productId: number }>(
    await api.post('/api/products', {
      data: {
        categoryId: categories[0].id,
        title,
        description: 'e2e 관심 담기 화면 여정용',
        price: 10_000,
        regionCode,
        imageUrls: ['https://e2e.local/sample.jpg'],
        thumbnailIndex: 0,
      },
    }),
    201,
  );
  return { productId: created.productId, title };
}

test('로그인한 사용자가 상품 상세에서 하트를 누르면 관심 목록에 담긴다', async ({
  page,
  user,
  otherUser,
}) => {
  // 남(otherUser)이 올린 상품을 준비한다 — 본인 상품에는 관심을 담을 수 없기 때문.
  const { productId } = await createProduct(otherUser.api);

  await test.step('로그인 화면에서 로그인한다', async () => {
    await page.goto('/login');
    await page.getByLabel('이메일').fill(user.email);
    await page.getByLabel('비밀번호').fill(user.password);
    // "카카오로 로그인"·"구글로 로그인"도 있어 exact 로 좁힌다(§9 strict mode).
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    // 로그인 성공 시에만 /products 로 이동한다 → 이동을 기다리는 것이 곧 "로그인 성공" 단언이다.
    await page.waitForURL('**/products');
    // ⚠️ 다음 페이지로 넘어가기 전에 이 페이지의 자동 로그인 복구(bootstrapAutoLogin)가 끝나길 기다린다.
    // 헤더가 '로그아웃' 을 보이면 = 재발급이 끝나 Refresh 쿠키가 안정화된 것. 이걸 안 기다리고 바로
    // goto 하면 재발급 도중 내비게이션이 끊겨 토큰만 회전(소모)되고 새 쿠키는 저장 안 돼, 다음 페이지가
    // 소모된 토큰으로 재발급→401→로그아웃 상태가 된다. (하드 내비게이션 + 회전형 Refresh 토큰의 함정)
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible({ timeout: 15_000 });
  });

  await test.step('상품 상세에서 관심(하트) 버튼을 누른다', async () => {
    await page.goto(`/products/${productId}`);

    // 하드 내비게이션마다 인메모리 토큰이 초기화되고 Refresh 쿠키로 재발급돼 복구된다.
    // toggleFav 는 클릭 시점에 getAccessToken() 을 동기로 확인하므로, 복구 전에 누르면
    // "로그인 후 이용할 수 있어요" 로 무시된다. 헤더가 '로그아웃' 을 보이면 = 인메모리 토큰이 복구된 것.
    // bootstrapAutoLogin 이 reissue + /members/me 를 순차로 밟아 복구가 수 초 걸릴 수 있어 넉넉히 기다린다.
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible({ timeout: 15_000 });

    const favoriteButton = page.getByRole('button', { name: '관심 상품 토글' });
    // 비소유자 뷰에 관심 버튼이 실제로 렌더되는지 확인(§9 소유자별 화면 분기).
    await expect(favoriteButton).toBeVisible();

    await favoriteButton.click();
    // setFavorited(true) 는 POST 응답 후에만 실행된다(page.tsx) → aria-pressed 가 곧 "담김이 서버에 반영됨" 장벽.
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'true');
  });

  await test.step('관심 목록 화면에 그 상품이 보인다', async () => {
    await page.goto('/favorites');
    // 화면 곳곳에 같은 텍스트가 중복될 수 있어(빵부스러기 등), 제목 텍스트 대신 상품 링크로 좁힌다(§9 strict mode).
    await expect(page.locator(`a[href="/products/${productId}"]`)).toBeVisible();
  });
});
