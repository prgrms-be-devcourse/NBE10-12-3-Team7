package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.RefreshToken
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.LocalDateTime
import java.util.Optional

/**
 * Redis 기반 구현체. 회원당 키 1개(`auth:refresh:{memberId}`)에 토큰 문자열을 저장하고,
 * TTL 을 Refresh Token 만료시간과 동일하게 둬서 만료 판정 자체를 Redis 에 위임한다.
 *
 * `SET` 은 있으면 덮어쓰고 없으면 새로 만드는 연산이라 JPA 구현과 달리 insert/update 분기가 필요 없다.
 *
 * 전환 규칙 — `@Value` 는 PARAMETER 를 허용하므로 `@param:Value` 로 생성자 파라미터에 명시한다
 * (`backend.md` 「값을 주입받는 어노테이션은 `@param:`」).
 */
@Repository
@Profile("!test")
class RedisRefreshTokenRepository(
    private val redisTemplate: StringRedisTemplate,
    @param:Value("\${jwt.refresh-token-validity-seconds}") private val refreshTokenValiditySeconds: Long,
) : RefreshTokenRepository {
    override fun findByMemberId(memberId: Long?): Optional<RefreshToken> {
        val key = key(memberId)
        val token = redisTemplate.opsForValue()[key] ?: return Optional.empty()
        return Optional.of(RefreshToken.issue(memberId, token, expiresAtFromTtl(key)))
    }

    override fun save(refreshToken: RefreshToken): RefreshToken {
        redisTemplate.opsForValue().set(
            key(refreshToken.memberId),
            refreshToken.token!!,
            Duration.ofSeconds(refreshTokenValiditySeconds),
        )
        return refreshToken
    }

    override fun deleteByMemberId(memberId: Long?) {
        redisTemplate.delete(key(memberId))
    }

    private fun key(memberId: Long?): String = KEY_PREFIX + memberId

    /**
     * 남은 TTL 로부터 만료 시각을 역산한다. `RefreshToken.expiresAt` 은 검증 로직에서 실제로 읽히지 않아
     * (JWT 의 exp 가 권위) 근사치로 충분하다.
     */
    private fun expiresAtFromTtl(key: String): LocalDateTime {
        val remainingSeconds = redisTemplate.getExpire(key)
        if (remainingSeconds == null || remainingSeconds < 0) {
            return LocalDateTime.now()
        }
        return LocalDateTime.now().plusSeconds(remainingSeconds)
    }

    companion object {
        private const val KEY_PREFIX = "auth:refresh:"
    }
}
