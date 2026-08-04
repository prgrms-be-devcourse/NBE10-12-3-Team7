import Redis from 'ioredis';
import { REDIS_URL } from './env';

/**
 * 이메일 인증 코드를 Redis 에서 직접 꺼내온다.
 *
 * 회원가입은 이메일 인증(verified=true)을 선행 조건으로 요구한다(AuthService.signup → EMAIL_NOT_VERIFIED).
 * e2e 에서 실제 메일함을 읽을 수는 없으니 코드가 저장된 곳에서 직접 읽는다.
 *
 * "테스트용 인증 우회 빈"을 백엔드에 심는 방법도 있지만, 그러면 테스트를 위해 프로덕션 경로를
 * 바꾸는 셈이고 정작 검증하려던 인증 흐름이 e2e 에서 빠져버린다. Redis 를 직접 읽으면
 * 프로덕션 코드는 한 줄도 건드리지 않으면서 실제 발급 경로를 그대로 통과한다.
 *
 * 키 규칙: RedisEmailVerificationCodeRepository.java 의 KEY_PREFIX 와 반드시 같아야 한다.
 */

const EMAIL_VERIFY_KEY_PREFIX = 'auth:email:verify:';

/**
 * 커넥션을 모듈 전역에 캐싱하면 테스트가 끝나도 소켓이 살아 있어 Playwright 프로세스가
 * 종료되지 않는다. 호출 빈도가 낮으니(가입할 때만) 매번 열고 닫는다.
 */
async function withRedis<T>(fn: (client: Redis) => Promise<T>): Promise<T> {
  const client = new Redis(REDIS_URL, { maxRetriesPerRequest: 2, lazyConnect: true });
  try {
    await client.connect();
    return await fn(client);
  } finally {
    await client.quit().catch(() => client.disconnect());
  }
}

/** 방금 발급된 이메일 인증 코드를 읽는다. 없으면 원인을 짚어주는 에러를 던진다. */
export async function readEmailVerificationCode(email: string): Promise<string> {
  const code = await withRedis((client) => client.get(`${EMAIL_VERIFY_KEY_PREFIX}${email}`));

  if (!code) {
    throw new Error(
      `이메일 인증 코드를 찾지 못했습니다: ${email}\n` +
        `- Redis(${REDIS_URL}) 가 떠 있는지: docker compose up -d --wait\n` +
        `- 백엔드가 InMemory 가 아닌 Redis 저장소를 쓰는 프로파일로 떠 있는지\n` +
        `- 키 접두사가 RedisEmailVerificationCodeRepository 와 같은지 (${EMAIL_VERIFY_KEY_PREFIX})`,
    );
  }
  return code;
}
