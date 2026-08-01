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
 * 전환 규칙 — [clear] 는 원본에 있던 **public** 메서드다(테스트 간 상태 격리용).
 * 인터페이스에 없는 구현체 고유 public API 라, 없애거나 가시성을 좁히면 호출부가 깨진다.
 */
@Repository
@Profile("test")
class InMemoryPasswordResetTokenRepository : PasswordResetTokenRepository {
    private data class Entry(
        val value: String,
        val expiresAt: Instant,
    )

    private val byMemberId = ConcurrentHashMap<Long?, Entry>()
    private val byTokenHash = ConcurrentHashMap<String?, Entry>()

    @Synchronized
    override fun save(
        memberId: Long?,
        tokenHash: String?,
        ttl: Duration?,
    ) {
        val expiresAt = Instant.now().plus(ttl)
        byMemberId[memberId] = Entry(tokenHash!!, expiresAt)
        byTokenHash[tokenHash] = Entry(memberId.toString(), expiresAt)
    }

    @Synchronized
    override fun findTokenHashByMemberId(memberId: Long?): Optional<String> = getIfNotExpired(byMemberId, memberId)

    @Synchronized
    override fun getRemainingTtlByMemberId(memberId: Long?): Optional<Duration> {
        val entry = byMemberId[memberId] ?: return Optional.empty()
        val remaining = Duration.between(Instant.now(), entry.expiresAt)
        if (remaining.isNegative) {
            byMemberId.remove(memberId)
            return Optional.empty()
        }
        return Optional.of(remaining)
    }

    @Synchronized
    override fun findMemberIdByTokenHash(tokenHash: String?): Optional<Long> = getIfNotExpired(byTokenHash, tokenHash).map { it.toLong() }

    @Synchronized
    override fun deleteByMemberId(memberId: Long?) {
        byMemberId.remove(memberId)
    }

    @Synchronized
    override fun deleteByTokenHash(tokenHash: String?) {
        byTokenHash.remove(tokenHash)
    }

    /** 테스트 간 상태 격리용(스프링 컨텍스트가 캐싱되어 빈이 테스트 클래스 간에도 공유되기 때문). */
    @Synchronized
    fun clear() {
        byMemberId.clear()
        byTokenHash.clear()
    }

    private fun <K> getIfNotExpired(
        map: ConcurrentHashMap<K, Entry>,
        key: K,
    ): Optional<String> {
        val entry = map[key]
        if (entry == null || Instant.now().isAfter(entry.expiresAt)) {
            map.remove(key)
            return Optional.empty()
        }
        return Optional.of(entry.value)
    }
}
