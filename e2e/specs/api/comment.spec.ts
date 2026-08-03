import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { expectError, unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';

/**
 * 댓글 — 작성/수정/삭제 생명주기와 작성자 권한 (권건우)
 *
 * 왜 e2e 인가: 권한 축. "남의 댓글은 수정·삭제할 수 없다(COMMENT_OWNER_ONLY)"는 시큐리티 필터를
 * 통과한 뒤 서비스가 작성자 본인인지 확인하는 경로라, 필터를 건너뛰는 단위 테스트로는 검증되지 않는다.
 * 코틀린 전환이 이 경로를 옮겼으므로 동작 불변을 여기서 증명한다.
 *
 * favorite·chat 과 달리 댓글은 소유권 제약이 없다 — 내 상품이든 남의 상품이든 댓글을 달 수 있다.
 */

/**
 * 상품 등록이 요구하는 "동(洞)" 지역코드를 시드된 지역 트리에서 런타임에 뽑는다.
 * 지역은 3단계 계층(level1 시/도 → level2 시/군/구 → level3 동)이고, 상품 등록은 level 3 만 받는다.
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

/** 상품 하나를 만든다. 댓글의 준비물일 뿐 검증 대상이 아니라 이 파일 안 로컬 함수로 둔다. */
async function createProduct(api: APIRequestContext): Promise<number> {
  const categories = await unwrap<{ id: number }[]>(await api.get('/api/categories'), 200);
  const regionCode = await pickDongRegionCode(api);

  const created = await unwrap<{ productId: number }>(
    await api.post('/api/products', {
      data: {
        categoryId: categories[0].id,
        title: uniqueTitle('상품'),
        description: 'e2e 댓글 테스트용',
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

interface Comment {
  id: number;
  content: string;
}

test.describe('댓글 — 작성/수정/삭제 생명주기와 작성자 권한', () => {
  test('댓글을 작성·수정하면 목록에 반영되고, 삭제하면 목록에서 빠진다', async ({ user }) => {
    const productId = await createProduct(user.api);

    const comment = await test.step('댓글을 작성하면 201 이다', async () => {
      const res = await user.api.post(`/api/products/${productId}/comments`, {
        data: { content: '첫 댓글입니다' },
      });
      return await unwrap<Comment>(res, 201);
    });

    await test.step('상품의 댓글 목록에 방금 쓴 댓글이 포함된다', async () => {
      // 전역 개수(toBe)는 병렬 실행에서 깨지므로 내 댓글 id 가 들어있는지만 본다.
      const list = await unwrap<Comment[]>(
        await user.api.get(`/api/products/${productId}/comments`),
        200,
      );
      expect(list.map((c) => c.id)).toContain(comment.id);
    });

    await test.step('내 댓글을 수정하면 내용이 바뀐다', async () => {
      const updated = await unwrap<Comment>(
        await user.api.patch(`/api/comments/${comment.id}`, { data: { content: '수정된 댓글' } }),
        200,
      );
      expect(updated.content).toBe('수정된 댓글');
    });

    await test.step('삭제하면 200 이고, 목록에서 빠진다(소프트 삭제)', async () => {
      await unwrap(await user.api.delete(`/api/comments/${comment.id}`), 200);

      const list = await unwrap<Comment[]>(
        await user.api.get(`/api/products/${productId}/comments`),
        200,
      );
      expect(list.map((c) => c.id)).not.toContain(comment.id);
    });
  });

  test('남의 댓글은 수정·삭제할 수 없다', async ({ user, otherUser }) => {
    const productId = await createProduct(user.api);
    const mine = await unwrap<Comment>(
      await user.api.post(`/api/products/${productId}/comments`, { data: { content: '내 댓글' } }),
      201,
    );

    await test.step('남이 내 댓글을 수정하면 COMMENT_OWNER_ONLY(403)', async () => {
      const res = await otherUser.api.patch(`/api/comments/${mine.id}`, {
        data: { content: '가로챈 수정' },
      });
      await expectError(res, 403, 'COMMENT_OWNER_ONLY');
    });

    await test.step('남이 내 댓글을 삭제하면 COMMENT_OWNER_ONLY(403)', async () => {
      const res = await otherUser.api.delete(`/api/comments/${mine.id}`);
      await expectError(res, 403, 'COMMENT_OWNER_ONLY');
    });
  });

  test('비로그인 사용자는 댓글을 작성할 수 없다', async ({ api, user }) => {
    const productId = await createProduct(user.api);

    const res = await api.post(`/api/products/${productId}/comments`, {
      data: { content: '비로그인 댓글' },
    });
    expect(res.status()).toBe(401);
  });

  test('존재하지 않는 댓글을 수정하면 COMMENT_NOT_FOUND(404)', async ({ user }) => {
    const res = await user.api.patch('/api/comments/99999999', { data: { content: '없는 댓글' } });
    await expectError(res, 404, 'COMMENT_NOT_FOUND');
  });
});

// ── PR 전 체크리스트 ──────────────────────────────────────────────────────────
// [x] 고정 ID/이메일/시드에 의존하지 않는가
// [x] 전역 개수를 단언하지 않는가 (toContain 으로 내 댓글만 확인)
// [x] 에러 케이스에서 ErrorCode 까지 단언했는가
// [x] npx playwright test specs/api/comment.spec.ts --repeat-each=3 통과 (develop RATE_LIMIT_CAPACITY 상향 반영본에서 그린)
