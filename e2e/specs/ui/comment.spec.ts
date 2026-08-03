import type { APIRequestContext, Page } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';

/**
 * 댓글 — 화면 여정 (권건우) : 로그인 → 상품 상세에서 댓글 작성 → 목록에 보인다
 *
 * 대표 여정 하나만 본다(§4). 권한·에러 계약은 specs/api/comment.spec.ts 가 담당한다.
 * 준비(회원·상품)는 API 로, 검증만 브라우저로 한다(§9).
 *
 * 인증 함정: 하드 내비게이션마다 인메모리 토큰이 사라지고 Refresh 쿠키로 복구된다. 댓글 입력창은
 * `getAccessToken()` 이 있어야 렌더되므로(page.tsx), 복구 전에는 입력창 자체가 없다. 그래서 이동 후
 * 헤더 '로그아웃'(=복구 완료)을 기다린 뒤 진행한다.
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

async function createProduct(api: APIRequestContext): Promise<number> {
  const categories = await unwrap<{ id: number }[]>(await api.get('/api/categories'), 200);
  const regionCode = await pickDongRegionCode(api);
  const created = await unwrap<{ productId: number }>(
    await api.post('/api/products', {
      data: {
        categoryId: categories[0].id,
        title: uniqueTitle('상품'),
        description: 'e2e 댓글 화면 여정용',
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

/** 로그인 화면에서 로그인하고, 복구가 끝나(헤더 '로그아웃') 이동 가능한 상태까지 만든다. */
async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('이메일').fill(email);
  await page.getByLabel('비밀번호').fill(password);
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await page.waitForURL('**/products');
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible({ timeout: 15_000 });
}

test('로그인한 사용자가 상품 상세에서 댓글을 남기면 목록에 보인다', async ({
  page,
  user,
  otherUser,
}) => {
  const productId = await createProduct(otherUser.api);

  await test.step('로그인한다', async () => {
    await loginViaUi(page, user.email, user.password);
  });

  await test.step('상품 상세에서 댓글을 작성한다', async () => {
    await page.goto(`/products/${productId}`);
    // 복구 완료(=입력창 렌더 조건인 인메모리 토큰 존재)를 기다린다.
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible({ timeout: 15_000 });

    const content = uniqueTitle('댓글');
    await page.getByPlaceholder('궁금한 점을 댓글로 남겨보세요.').fill(content);
    await page.getByRole('button', { name: '등록' }).click();

    // 작성한 댓글이 목록에 나타난다(내용은 unique 라 화면에서 유일하게 식별된다).
    await expect(page.getByText(content)).toBeVisible();
  });
});
