package com.dongnemarket.auth.repository

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * `test` 프로파일 전용 구현체. 외부 Redis 없이 `./gradlew test` 가 통과하도록
 * 메모리 맵 + 만료시각으로 TTL 동작을 흉내 낸다(윈도우가 지나면 조회 시점에 lazy 하게 초기화).
 *
 * 전환 규칙 — 원본의 "최초 실패에만 만료시각을 새로 잡고, 윈도우 내 재실패는 기존 만료시각을 유지"하는
 * 정책을 그대로 옮긴다. TTL 연장이 일어나면 차단 시간이 늘어나 동작이 달라진다.
 */
@Repository
@Profile("test")
class InMemoryLoginAttemptRepository(
    private val clock: Clock = Clock.systemUTC(),
) : LoginAttemptRepository {
    private data class Entry(
        val count: Long,
        val expiresAt: Instant,
    ) {
        fun isExpired(now: Instant): Boolean = now.isAfter(expiresAt)
    }

    private val store = ConcurrentHashMap<String?, Entry>()

    @Synchronized
    override fun getFailureCount(email: String?): Long {
        val entry = store[email]
        if (entry == null || entry.isExpired(Instant.now(clock))) {
            return 0L
        }
        return entry.count
    }

    @Synchronized
    override fun incrementFailure(
        email: String?,
        lockWindow: Duration?,
    ) {
        val now = Instant.now(clock)
        val existing = store[email]
        if (existing == null || existing.isExpired(now)) {
            store[email] = Entry(1L, now.plus(lockWindow))
            return
        }
        store[email] = Entry(existing.count + 1, existing.expiresAt)
    }

    @Synchronized
    override fun resetFailure(email: String?) {
        store.remove(email)
    }
}
