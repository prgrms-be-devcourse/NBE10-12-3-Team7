package com.dongnemarket.auth.repository

import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.Optional

/**
 * Redis 기반 구현체. member→tokenHash(`auth:password:reset:member:{memberId}`)와
 * tokenHash→memberId(`auth:password:reset:token:{tokenHash}`) 두 키를 항상 같은 TTL 로 함께 관리한다.
 *
 * 전환 규칙 — 두 키의 prefix 문자열과 저장 순서(member 먼저, token 나중)를 그대로 옮긴다.
 * memberId 는 `String.valueOf` 와 동일하게 문자열로 저장한다.
 */
@Repository
@Profile("!test")
class RedisPasswordResetTokenRepository(
    private val redisTemplate: StringRedisTemplate,
) : PasswordResetTokenRepository {
    override fun save(
        memberId: Long?,
        tokenHash: String?,
        ttl: Duration?,
    ) {
        redisTemplate.opsForValue().set(memberKey(memberId), tokenHash!!, ttl!!)
        redisTemplate.opsForValue().set(tokenKey(tokenHash), memberId.toString(), ttl)
    }

    override fun findTokenHashByMemberId(memberId: Long?): Optional<String> =
        Optional.ofNullable(redisTemplate.opsForValue()[memberKey(memberId)])

    override fun getRemainingTtlByMemberId(memberId: Long?): Optional<Duration> {
        val remainingSeconds = redisTemplate.getExpire(memberKey(memberId))
        if (remainingSeconds == null || remainingSeconds < 0) {
            return Optional.empty()
        }
        return Optional.of(Duration.ofSeconds(remainingSeconds))
    }

    override fun findMemberIdByTokenHash(tokenHash: String?): Optional<Long> {
        val value = redisTemplate.opsForValue()[tokenKey(tokenHash)]
        return if (value == null) Optional.empty() else Optional.of(value.toLong())
    }

    override fun deleteByMemberId(memberId: Long?) {
        redisTemplate.delete(memberKey(memberId))
    }

    override fun deleteByTokenHash(tokenHash: String?) {
        redisTemplate.delete(tokenKey(tokenHash))
    }

    private fun memberKey(memberId: Long?): String = MEMBER_KEY_PREFIX + memberId

    private fun tokenKey(tokenHash: String?): String = TOKEN_KEY_PREFIX + tokenHash

    companion object {
        private const val MEMBER_KEY_PREFIX = "auth:password:reset:member:"
        private const val TOKEN_KEY_PREFIX = "auth:password:reset:token:"
    }
}
