import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { unwrap } from '../../support/api';
import { uniqueTitle } from '../../support/unique';

/**
 * 알림 — 댓글에서 파생되는 알림과 읽음 처리 (권건우)
 *
 * 왜 e2e 인가: 크로스도메인 상태 축. 알림은 저장되는 게 아니라 "댓글 작성"이라는 다른 도메인의
 * 이벤트에서 파생된다(CommentCreatedEvent → NotificationEventHandler, AFTER_COMMIT + REQUIRES_NEW).
 * 이벤트 배선이 끊기면 단위 테스트는 각자 통과해도 알림은 영영 안 생긴다 — 이건 두 도메인이 실제로
 * 함께 도는 e2e 에서만 드러난다.
 *
 * 파생 규칙(CommentService): 수신자 = 상품 주인. 단 `상품주인 == 작성자`면 발행하지 않는다
 * (자기 상품에 자기가 댓글 달아도 알림 없음). 그래서 알림을 받으려면 *남*(otherUser)이 내 상품에 댓글을 달아야 한다.
 *
 * 리스너는 @Async 가 아니라 AFTER_COMMIT(요청 스레드) 이므로 응답 시점엔 이미 저장돼 있다.
 * 그래도 파생 값은 expect.poll 로 감싼다 — 동기라면 즉시 통과하고, 혹시 모를 타이밍에도 안전하다(비용 0).
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

/** 상품 하나를 만든다. 알림의 준비물일 뿐 검증 대상이 아니라 이 파일 안 로컬 함수로 둔다. */
async function createProduct(api: APIRequestContext): Promise<number> {
  const categories = await unwrap<{ id: number }[]>(await api.get('/api/categories'), 200);
  const regionCode = await pickDongRegionCode(api);

  const created = await unwrap<{ productId: number }>(
    await api.post('/api/products', {
      data: {
        categoryId: categories[0].id,
        title: uniqueTitle('상품'),
        description: 'e2e 알림 테스트용',
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

interface Notification {
  productId: number | null;
  message: string;
  isRead: boolean;
}

async function myNotifications(api: APIRequestContext): Promise<Notification[]> {
  return unwrap<Notification[]>(await api.get('/api/notifications'), 200);
}
async function myUnreadCount(api: APIRequestContext): Promise<number> {
  const res = await unwrap<{ unreadCount: number }>(await api.get('/api/notifications/unread-count'), 200);
  return res.unreadCount;
}

test.describe('알림 — 댓글에서 파생되는 알림과 읽음 처리', () => {
  test('내 상품에 남이 댓글을 달면 알림이 생기고, 읽음 처리하면 안읽음 수가 0이 된다', async ({
    user,
    otherUser,
  }) => {
    // 알림 수신자(user)는 갓 가입한 회원이라 시작 시 안읽음 0 이다(전역 상태 아님, 내 계정 한정).
    const productId = await createProduct(user.api);
    expect(await myUnreadCount(user.api), '새 회원은 안읽음 알림이 0 이어야 한다').toBe(0);

    await test.step('남(otherUser)이 내 상품에 댓글을 단다', async () => {
      await unwrap(
        await otherUser.api.post(`/api/products/${productId}/comments`, {
          data: { content: '이 상품 아직 판매하나요?' },
        }),
        201,
      );
    });

    await test.step('내 알림 목록에 그 상품에 대한 알림이 생긴다', async () => {
      await expect
        .poll(async () => (await myNotifications(user.api)).some((n) => n.productId === productId))
        .toBe(true);
    });

    await test.step('안읽음 알림 수가 올라간다', async () => {
      await expect.poll(async () => await myUnreadCount(user.api)).toBeGreaterThan(0);
    });

    await test.step('전체 읽음 처리하면 안읽음 수가 0 이 된다', async () => {
      await unwrap(await user.api.post('/api/notifications/read'), 200);
      expect(await myUnreadCount(user.api)).toBe(0);

      const after = (await myNotifications(user.api)).find((n) => n.productId === productId);
      expect(after?.isRead, '읽음 처리 후 해당 알림은 읽음 상태여야 한다').toBe(true);
    });
  });

  test('자기 상품에 자기가 단 댓글은 알림을 만들지 않는다', async ({ user }) => {
    const productId = await createProduct(user.api);

    await unwrap(
      await user.api.post(`/api/products/${productId}/comments`, {
        data: { content: '자문자답 댓글' },
      }),
      201,
    );

    // 리스너가 동기(AFTER_COMMIT)라 응답 시점에 이미 처리가 끝났다 → 없음은 결정적이다(poll 불필요).
    const list = await myNotifications(user.api);
    expect(list.map((n) => n.productId)).not.toContain(productId);
    expect(await myUnreadCount(user.api)).toBe(0);
  });

  test('비로그인 사용자는 알림을 조회할 수 없다', async ({ api }) => {
    const res = await api.get('/api/notifications');
    expect(res.status()).toBe(401);
  });
});

// ── PR 전 체크리스트 ──────────────────────────────────────────────────────────
// [x] 고정 ID/이메일/시드에 의존하지 않는가 (수신자 회원·상품을 매번 새로 만든다)
// [x] 전역 개수를 단언하지 않는가 (내 상품 productId 로 내 알림만 식별)
// [x] 파생 값은 poll 로 감싸 async 리스너에도 안전한가 (양성만; 음성은 결정적이라 직접 단언)
// [x] npx playwright test specs/api/notification.spec.ts --repeat-each=3 통과 (develop RATE_LIMIT_CAPACITY 상향 반영본에서 그린)
