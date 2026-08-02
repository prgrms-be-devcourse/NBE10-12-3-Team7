package com.dongnemarket.auth.repository

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * `test` 프로파일 전용 구현체. 외부 Redis 없이 `./gradlew test` 가 통과하도록
 * 메모리 맵 + 만료시각으로 TTL 동작을 흉내 낸다(조회 시점에 lazy 하게 만료 처리).
 *
 * 전환 규칙 — 원본의 `ConcurrentHashMap` 과 메서드 단위 `synchronized` 를 그대로 유지한다.
 * Kotlin 은 `@Synchronized` 로 같은 JVM 표면(`ACC_SYNCHRONIZED`)을 만든다.
 */
@Repository
@Profile("test")
class InMemoryEmailVerificationCodeRepository : EmailVerificationCodeRepository {
    private data class Entry(
        val code: String,
        val expiresAt: Instant,
    )

    private val store = ConcurrentHashMap<String?, Entry>()

    @Synchronized
    override fun save(
        email: String?,
        code: String?,
        ttl: Duration?,
    ) {
        store[email] = Entry(code!!, Instant.now().plus(ttl))
    }

    @Synchronized
    override fun findCode(email: String?): Optional<String> {
        val entry = store[email]
        if (entry == null || Instant.now().isAfter(entry.expiresAt)) {
            store.remove(email)
            return Optional.empty()
        }
        return Optional.of(entry.code)
    }

    @Synchronized
    override fun getRemainingTtl(email: String?): Optional<Duration> {
        val entry = store[email] ?: return Optional.empty()
        val remaining = Duration.between(Instant.now(), entry.expiresAt)
        if (remaining.isNegative) {
            store.remove(email)
            return Optional.empty()
        }
        return Optional.of(remaining)
    }

    @Synchronized
    override fun delete(email: String?) {
        store.remove(email)
    }
}
