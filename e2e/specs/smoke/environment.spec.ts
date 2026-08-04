import { test, expect } from '../../fixtures/test';
import { unwrap } from '../../support/api';
import { loginAsAdmin, signUpAndLogin, verifyEmail } from '../../fixtures/auth';
import { uniqueEmail } from '../../support/unique';
import { MAILPIT_URL } from '../../support/env';

/**
 * 환경 스모크 — 팀장 소유. 도메인 로직은 여기서 검증하지 않는다.
 *
 * 목적은 하나다: 팀원이 자기 스펙이 깨졌을 때 **"내 코드 문제인가, 환경 문제인가"를 30초 안에 가르는 것.**
 *
 *   npm run smoke
 *
 * 여기가 통과하는데 스펙이 깨지면 → 스펙(또는 백엔드 로직) 문제다.
 * 여기가 깨지면 → 환경 문제다. 스펙을 들여다볼 필요가 없다.
 *
 * 이 파일은 도메인이 아니라 인프라를 검증하므로, 백엔드가 코틀린으로 전환되어도 바뀌지 않는다.
 */

test.describe('환경 스모크', () => {
  test('백엔드가 살아 있다', async ({ api }) => {
    const res = await api.get('/actuator/health');
    expect(res.status()).toBe(200);
    expect((await res.json()).status).toBe('UP');
  });

  test('시큐리티 필터가 붙어 있다 — 비인증 요청은 401', async ({ api }) => {
    const res = await api.get('/api/members/me');
    expect(res.status()).toBe(401);
  });

  test('마스터 데이터가 시딩되어 있다 — 지역·카테고리', async ({ api }) => {
    // RegionSeeder / CategorySeeder 가 기동 시 채운다. 비어 있으면 상품 등록 계열 스펙이 전부 죽는다.
    const regions = await unwrap<unknown[]>(await api.get('/api/regions'), 200);
    expect(regions.length, '지역 시딩이 안 됐다 (RegionSeeder 확인)').toBeGreaterThan(0);

    const categories = await unwrap<unknown[]>(await api.get('/api/categories'), 200);
    expect(categories.length, '카테고리 시딩이 안 됐다 (CategorySeeder 확인)').toBeGreaterThan(0);
  });

  test('관리자 계정이 존재하고 로그인된다', async ({ api }) => {
    // AdminSeeder 가 만드는 계정. admin 픽스처가 이걸 쓴다.
    const accessToken = await loginAsAdmin(api);
    expect(accessToken).toBeTruthy();
  });

  test('메일 발송 경로가 열려 있다 — Mailpit 에 실제로 도착한다', async ({ api }) => {
    // 이 프로젝트에서 e2e 가 가장 잘 막히는 지점이다.
    // SMTP 가 없으면 여기서 500 EMAIL_SEND_FAILED 로 죽고, 가입이 필요한 모든 스펙이 함께 죽는다.
    const email = uniqueEmail('smoke');

    const requested = await api.post('/api/auth/email-verifications', { data: { email } });
    await unwrap(requested, 201);

    const search = await fetch(`${MAILPIT_URL}/api/v1/search?query=${encodeURIComponent(email)}`);
    expect(search.ok, `Mailpit(${MAILPIT_URL}) 에 접근할 수 없다`).toBe(true);

    const { messages } = (await search.json()) as { messages: unknown[] };
    expect(messages.length, '인증 메일이 Mailpit 에 도착하지 않았다').toBeGreaterThan(0);
  });

  test('픽스처 4종이 모두 동작한다 — 팀원이 첫날 쓰는 것들', async ({ user, otherUser, admin }) => {
    // 팀원 스펙은 이 픽스처들 위에 올라간다. 여기가 통과하면 픽스처 문제로 막힐 일은 없다.
    await test.step('user / otherUser 는 서로 다른 회원이다', async () => {
      expect(user.memberId).not.toBe(otherUser.memberId);

      const mine = await unwrap<{ email: string }>(await user.api.get('/api/members/me'), 200);
      const theirs = await unwrap<{ email: string }>(await otherUser.api.get('/api/members/me'), 200);
      expect(mine.email).toBe(user.email);
      expect(theirs.email).toBe(otherUser.email);
    });

    await test.step('admin 은 관리자 API 에 접근할 수 있다', async () => {
      const res = await admin.get('/api/admin/dashboard');
      expect(res.status(), '관리자 권한이 안 붙었다 (AdminSeeder / ROLE 확인)').toBe(200);
    });

    await test.step('일반 회원은 관리자 API 에 접근할 수 없다', async () => {
      const res = await user.api.get('/api/admin/dashboard');
      expect(res.status(), '권한 분리가 동작하지 않는다').toBe(403);
    });
  });

  test('가입 전 여정이 통과한다 — 픽스처가 정상 동작한다', async ({ api }) => {
    // user / otherUser 픽스처가 내부적으로 밟는 경로 그대로다(이메일 인증 → 가입 → 로그인).
    // 여기가 통과하면 팀원 스펙의 `user` 픽스처는 반드시 동작한다.
    const email = uniqueEmail('smoke');
    await verifyEmail(api, email);

    const credentials = await signUpAndLogin(api);
    expect(credentials.accessToken).toBeTruthy();
    expect(credentials.memberId).toBeGreaterThan(0);
  });
});
