package com.dongnemarket.auth.repository

import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

/**
 * Redis 기반 구현체. 이메일당 키 1개(`auth:login:fail:{email}`)에 실패 횟수를 담고,
 * 최초 실패 시점부터 고정 윈도우(TTL)가 흐르게 해 "N회 실패 시 M분 차단"을 자연스럽게 구현한다.
 *
 * 전환 규칙 — `INCR` 결과가 정확히 1일 때만 `EXPIRE` 를 거는 순서를 유지한다.
 * 매번 걸면 윈도우가 계속 연장돼 차단이 풀리지 않는다.
 */
@Repository
@Profile("!test")
class RedisLoginAttemptRepository(
    private val redisTemplate: StringRedisTemplate,
) : LoginAttemptRepository {
    override fun getFailureCount(email: String?): Long {
        val value = redisTemplate.opsForValue()[key(email)]
        return if (value == null) 0L else value.toLong()
    }

    override fun incrementFailure(
        email: String?,
        lockWindow: Duration?,
    ) {
        val key = key(email)
        val count = redisTemplate.opsForValue().increment(key)
        if (count != null && count == 1L) {
            redisTemplate.expire(key, lockWindow!!)
        }
    }

    override fun resetFailure(email: String?) {
        redisTemplate.delete(key(email))
    }

    private fun key(email: String?): String = KEY_PREFIX + email

    companion object {
        private const val KEY_PREFIX = "auth:login:fail:"
    }
}
