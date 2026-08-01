package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.OAuthProvider
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * `test` 프로파일 전용 구현체. 외부 Redis 없이 `./gradlew test` 가 통과하도록
 * 메모리 맵 + 만료시각으로 동작을 흉내 낸다. 단일 JVM 내 테스트만 대상이라 `synchronized` 로
 * [RedisOAuthStateRepository] 의 Lua 원자성과 동등한 효과를 낸다.
 *
 * 전환 규칙 — 발급 시 "만료 항목 제거 → 브라우저별 개수 확인 → 저장" 순서와,
 * 소비 시 "만료면 삭제 후 empty / 불일치면 **삭제하지 않고** empty" 라는 구분을 그대로 유지한다.
 * 불일치일 때 삭제해 버리면 공격자가 정상 state 를 소모시킬 수 있다.
 */
@Repository
@Profile("test")
class InMemoryOAuthStateRepository : OAuthStateRepository {
    private data class Entry(
        val value: OAuthAuthorizationState,
        val expiresAt: Instant,
    ) {
        fun isExpired(now: Instant): Boolean = now.isAfter(expiresAt)
    }

    private val states: MutableMap<String?, Entry> = ConcurrentHashMap()

    @Synchronized
    override fun issue(
        state: String?,
        value: OAuthAuthorizationState,
        ttl: Duration?,
        maxPendingPerBrowser: Int,
    ): Boolean {
        val now = Instant.now()
        states.entries.removeIf { it.value.isExpired(now) }

        val pendingForBrowser =
            states.values.count { it.value.browserCorrelationHash == value.browserCorrelationHash }
        if (pendingForBrowser >= maxPendingPerBrowser) {
            return false
        }

        states[state] = Entry(value, now.plus(ttl))
        return true
    }

    @Synchronized
    override fun consume(
        state: String?,
        provider: OAuthProvider?,
        browserCorrelationHash: String?,
    ): Optional<OAuthAuthorizationState> {
        val entry = states[state]
        if (entry == null || entry.isExpired(Instant.now())) {
            states.remove(state)
            return Optional.empty()
        }
        if (entry.value.provider !== provider || entry.value.browserCorrelationHash != browserCorrelationHash) {
            return Optional.empty()
        }
        states.remove(state)
        return Optional.of(entry.value)
    }
}
