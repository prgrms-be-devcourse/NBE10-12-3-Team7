import type { APIRequestContext } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { expectError, unwrap } from '../../support/api';
import { bearer, signUpAndLogin } from '../../fixtures/auth';
import { uniqueTitle } from '../../support/unique';
import { BACKEND_URL } from '../../support/env';

/**
 * 채팅 — 방 연결(get-or-create)·메시지 송수신·참여자 권한 (권건우)
 *
 * 왜 e2e 인가: 권한·상태 축. "참여자만 방에 접근(CHAT_ACCESS_DENIED)", "본인 상품엔 채팅 불가
 * (CANNOT_CHAT_WITH_SELF)", "같은 상품 재연결은 같은 방(get-or-create 멱등)"은 여러 테이블
 * (방·메시지·읽음지점)과 시큐리티에 걸쳐 있어 단위 테스트가 통째로 검증하지 못한다.
 *
 * 이 스펙은 REST 계약만 검증한다. 메시지 전송 시의 STOMP 브로드캐스트는 픽스처가 제공하지 않는
 * WebSocket 클라이언트가 필요하고 네 축(돈/권한/상태/동시성)에도 해당하지 않아 여기서 다루지 않는다.
 *
 * 소유권 방향: 구매자(user)가 판매자(otherUser)의 상품에 채팅을 연다. 본인 상품이면 400 이다.
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

/** 상품 하나를 만든다. 채팅의 준비물일 뿐 검증 대상이 아니라 이 파일 안 로컬 함수로 둔다. */
async function createProduct(api: APIRequestContext): Promise<number> {
  const categories = await unwrap<{ id: number }[]>(await api.get('/api/categories'), 200);
  const regionCode = await pickDongRegionCode(api);

  const created = await unwrap<{ productId: number }>(
    await api.post('/api/products', {
      data: {
        categoryId: categories[0].id,
        title: uniqueTitle('상품'),
        description: 'e2e 채팅 테스트용',
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

interface RoomDetail {
  roomId: number;
}
interface ChatMessage {
  messageId: number;
  senderId: number;
  content: string;
}
interface MessagePage {
  messages: ChatMessage[];
  hasNext: boolean;
}

test.describe('채팅 — 방 연결·메시지 송수신·참여자 권한', () => {
  test('상품에 채팅을 열고 메시지를 주고받으면 상대에게 보이고 읽음 처리된다', async ({
    user,
    otherUser,
  }) => {
    // 판매자(otherUser)의 상품에 구매자(user)가 채팅을 연다.
    const productId = await createProduct(otherUser.api);

    const roomId = await test.step('상품에 채팅방을 연결하면 200 이다', async () => {
      const room = await unwrap<RoomDetail>(
        await user.api.post('/api/chat-rooms', { data: { productId } }),
        200,
      );
      expect(room.roomId).toBeGreaterThan(0);
      return room.roomId;
    });

    await test.step('같은 상품에 다시 연결하면 새 방이 아니라 같은 방이다 (get-or-create 멱등)', async () => {
      const again = await unwrap<RoomDetail>(
        await user.api.post('/api/chat-rooms', { data: { productId } }),
        200,
      );
      expect(again.roomId).toBe(roomId);
    });

    const sent = await test.step('구매자가 메시지를 보내면 201 이다', async () => {
      const res = await user.api.post(`/api/chat-rooms/${roomId}/messages`, {
        data: { content: '안녕하세요, 구매 문의드립니다' },
      });
      const msg = await unwrap<ChatMessage>(res, 201);
      expect(msg.senderId).toBe(user.memberId);
      return msg;
    });

    await test.step('판매자가 방 메시지를 조회하면 그 메시지가 보인다', async () => {
      const page = await unwrap<MessagePage>(
        await otherUser.api.get(`/api/chat-rooms/${roomId}/messages`),
        200,
      );
      expect(page.messages.map((m) => m.messageId)).toContain(sent.messageId);
    });

    await test.step('판매자가 읽음 처리하면 200 이다', async () => {
      await unwrap(await otherUser.api.post(`/api/chat-rooms/${roomId}/read`), 200);
    });
  });

  test('본인 상품에는 채팅을 시작할 수 없다', async ({ user }) => {
    const myProductId = await createProduct(user.api);

    const res = await user.api.post('/api/chat-rooms', { data: { productId: myProductId } });
    await expectError(res, 400, 'CANNOT_CHAT_WITH_SELF');
  });

  test('채팅방 참여자가 아니면 메시지를 조회할 수 없다', async ({ user, otherUser, playwright }) => {
    const productId = await createProduct(otherUser.api);
    const room = await unwrap<RoomDetail>(
      await user.api.post('/api/chat-rooms', { data: { productId } }),
      200,
    );

    // 픽스처는 user/otherUser/admin 3종뿐이라, 방과 무관한 "제3자"를 인라인으로 하나 만든다.
    const bootstrap = await playwright.request.newContext({ baseURL: BACKEND_URL });
    const stranger = await signUpAndLogin(bootstrap);
    await bootstrap.dispose();

    const strangerApi = await playwright.request.newContext({
      baseURL: BACKEND_URL,
      extraHTTPHeaders: bearer(stranger.accessToken),
    });

    const res = await strangerApi.get(`/api/chat-rooms/${room.roomId}/messages`);
    await expectError(res, 403, 'CHAT_ACCESS_DENIED');

    await strangerApi.dispose();
  });

  test('비로그인 사용자는 채팅방을 열 수 없다', async ({ api, otherUser }) => {
    const productId = await createProduct(otherUser.api);

    const res = await api.post('/api/chat-rooms', { data: { productId } });
    expect(res.status()).toBe(401);
  });
});

// ── PR 전 체크리스트 ──────────────────────────────────────────────────────────
// [x] 고정 ID/이메일/시드에 의존하지 않는가 (상품·회원·방을 매번 새로 만든다)
// [x] 전역 개수를 단언하지 않는가 (toContain 으로 내 메시지만 확인)
// [x] 에러 케이스에서 ErrorCode 까지 단언했는가
// [x] STOMP 브로드캐스트는 범위에서 제외(REST 계약만) — 근거는 파일 상단 주석
// [x] npx playwright test specs/api/chat.spec.ts --repeat-each=3 통과 (develop RATE_LIMIT_CAPACITY 상향 반영본에서 그린)
