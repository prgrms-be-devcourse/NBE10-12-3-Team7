package com.dongnemarket.auth.repository

import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.Optional

/**
 * Redis 기반 구현체. 이메일당 키 1개(`auth:email:verify:{email}`)에 코드를 저장하고,
 * TTL 을 인증 코드 유효기간과 동일하게 둬서 만료 판정 자체를 Redis 에 위임한다.
 *
 * 전환 규칙 — key prefix 문자열과 TTL 처리(`getExpire` 가 음수면 empty)를 그대로 옮긴다.
 * 상수는 `private const val` 이라 원본의 `private static final` 과 같은 자리에 남는다.
 */
@Repository
@Profile("!test")
class RedisEmailVerificationCodeRepository(
    private val redisTemplate: StringRedisTemplate,
) : EmailVerificationCodeRepository {
    override fun save(
        email: String?,
        code: String?,
        ttl: Duration?,
    ) {
        redisTemplate.opsForValue().set(key(email), code!!, ttl!!)
    }

    override fun findCode(email: String?): Optional<String> = Optional.ofNullable(redisTemplate.opsForValue()[key(email)])

    override fun getRemainingTtl(email: String?): Optional<Duration> {
        val remainingSeconds = redisTemplate.getExpire(key(email))
        if (remainingSeconds == null || remainingSeconds < 0) {
            return Optional.empty()
        }
        return Optional.of(Duration.ofSeconds(remainingSeconds))
    }

    override fun delete(email: String?) {
        redisTemplate.delete(key(email))
    }

    private fun key(email: String?): String = KEY_PREFIX + email

    companion object {
        private const val KEY_PREFIX = "auth:email:verify:"
    }
}
