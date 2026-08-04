package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.OAuthProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * 3단계 InMemory 구현 4개의 **동작 계약**을 고정한다. Kotlin 전환에서 조용히 달라질 수 있는 것들
 * — 만료 판정 시점, 덮어쓰기 정책, consume 후 삭제 여부, TTL 연장 여부 — 을 직접 검증한다.
 *
 * Redis 구현의 key·TTL·Lua 원자성은 기존 Testcontainers 통합 테스트(`integrationTest`)가 이미 담당하므로
 * 여기서 중복하지 않는다. 두 구현이 같은 추상화를 만족한다는 사실은 그 통합 테스트와 이 테스트가
 * 각각의 쪽에서 확인한다.
 *
 * 빈을 주입받지 않고 직접 생성한다 — 스프링 컨텍스트가 캐싱되면 테스트 클래스 간에 상태가 공유돼
 * 만료·덮어쓰기 검증이 서로 간섭한다.
 */
class AuthStoreContractTest {
    @Nested
    @DisplayName("InMemoryEmailVerificationCodeRepository")
    inner class EmailVerificationCode {
        private val repository = InMemoryEmailVerificationCodeRepository()

        @Test
        fun `저장 후 조회되고 삭제하면 사라진다`() {
            repository.save("a@b.com", "123456", Duration.ofMinutes(5))
            assertThat(repository.findCode("a@b.com")).contains("123456")

            repository.delete("a@b.com")
            assertThat(repository.findCode("a@b.com")).isEmpty()
        }

        @Test
        fun `재요청하면 새 코드로 덮어쓴다`() {
            repository.save("a@b.com", "111111", Duration.ofMinutes(5))
            repository.save("a@b.com", "222222", Duration.ofMinutes(5))
            assertThat(repository.findCode("a@b.com")).contains("222222")
        }

        @Test
        fun `만료된 코드는 조회되지 않는다`() {
            repository.save("a@b.com", "123456", Duration.ofMillis(-1))
            assertThat(repository.findCode("a@b.com")).isEmpty()
            assertThat(repository.getRemainingTtl("a@b.com")).isEmpty()
        }

        @Test
        fun `남은 TTL 은 저장한 값 이하의 양수다`() {
            repository.save("a@b.com", "123456", Duration.ofMinutes(5))
            val remaining = repository.getRemainingTtl("a@b.com")
            assertThat(remaining).isPresent()
            assertThat(remaining.get()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(5))
        }

        @Test
        fun `없는 이메일의 TTL 은 empty 다`() {
            assertThat(repository.getRemainingTtl("none@b.com")).isEmpty()
        }
    }

    @Nested
    @DisplayName("InMemoryLoginAttemptRepository")
    inner class LoginAttempt {
        private val repository = InMemoryLoginAttemptRepository()

        @Test
        fun `실패가 없으면 0 이다`() {
            assertThat(repository.getFailureCount("a@b.com")).isZero()
        }

        @Test
        fun `실패마다 1씩 증가한다`() {
            repeat(3) { repository.incrementFailure("a@b.com", Duration.ofMinutes(10)) }
            assertThat(repository.getFailureCount("a@b.com")).isEqualTo(3L)
        }

        /**
         * 최초 실패에만 윈도우를 잡는다 — 매번 연장하면 차단이 풀리지 않는다.
         * 시간 경과가 본질인 검증이라 시스템 시계 대신 [MutableClock] 으로 타임라인을 직접 굴린다.
         * t=120ms 정확 경계의 포함 여부는 계약에 명시돼 있지 않으므로 119ms/121ms 로만 판정한다.
         */
        @Test
        fun `윈도우 내 반복 실패는 만료 시각을 연장하지 않는다`() {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val repository = InMemoryLoginAttemptRepository(clock)

            repository.incrementFailure("a@b.com", Duration.ofMillis(120))

            clock.advance(Duration.ofMillis(60))
            repository.incrementFailure("a@b.com", Duration.ofMinutes(10))
            assertThat(repository.getFailureCount("a@b.com")).isEqualTo(2L)

            clock.advance(Duration.ofMillis(59)) // t=119ms — 최초 윈도우 안
            assertThat(repository.getFailureCount("a@b.com")).isEqualTo(2L)

            clock.advance(Duration.ofMillis(2)) // t=121ms — 두 번째 호출이 연장했다면 2가 남는다
            assertThat(repository.getFailureCount("a@b.com")).isZero()
        }

        @Test
        fun `윈도우가 지나면 0 으로 초기화된다`() {
            repository.incrementFailure("a@b.com", Duration.ofMillis(-1))
            assertThat(repository.getFailureCount("a@b.com")).isZero()
        }

        @Test
        fun `만료된 뒤 실패하면 다시 1 부터 센다`() {
            repository.incrementFailure("a@b.com", Duration.ofMillis(-1))
            repository.incrementFailure("a@b.com", Duration.ofMinutes(10))
            assertThat(repository.getFailureCount("a@b.com")).isEqualTo(1L)
        }

        @Test
        fun `로그인 성공 시 초기화된다`() {
            repository.incrementFailure("a@b.com", Duration.ofMinutes(10))
            repository.resetFailure("a@b.com")
            assertThat(repository.getFailureCount("a@b.com")).isZero()
        }
    }

    @Nested
    @DisplayName("InMemoryPasswordResetTokenRepository — 양방향 2-key")
    inner class PasswordResetToken {
        private val repository = InMemoryPasswordResetTokenRepository()

        @Test
        fun `양방향으로 조회된다`() {
            repository.save(1L, "hash-1", Duration.ofMinutes(30))
            assertThat(repository.findTokenHashByMemberId(1L)).contains("hash-1")
            assertThat(repository.findMemberIdByTokenHash("hash-1")).contains(1L)
        }

        @Test
        fun `memberId 로 삭제해도 tokenHash 키는 남는다 - 원본과 동일한 분리 삭제`() {
            repository.save(1L, "hash-1", Duration.ofMinutes(30))
            repository.deleteByMemberId(1L)

            assertThat(repository.findTokenHashByMemberId(1L)).isEmpty()
            assertThat(repository.findMemberIdByTokenHash("hash-1")).contains(1L)
        }

        @Test
        fun `tokenHash 로도 삭제된다`() {
            repository.save(1L, "hash-1", Duration.ofMinutes(30))
            repository.deleteByTokenHash("hash-1")
            assertThat(repository.findMemberIdByTokenHash("hash-1")).isEmpty()
        }

        @Test
        fun `만료되면 양쪽 모두 조회되지 않는다`() {
            repository.save(1L, "hash-1", Duration.ofMillis(-1))
            assertThat(repository.findTokenHashByMemberId(1L)).isEmpty()
            assertThat(repository.findMemberIdByTokenHash("hash-1")).isEmpty()
            assertThat(repository.getRemainingTtlByMemberId(1L)).isEmpty()
        }

        @Test
        fun `clear 로 상태가 비워진다`() {
            repository.save(1L, "hash-1", Duration.ofMinutes(30))
            repository.clear()
            assertThat(repository.findTokenHashByMemberId(1L)).isEmpty()
            assertThat(repository.findMemberIdByTokenHash("hash-1")).isEmpty()
        }
    }

    @Nested
    @DisplayName("InMemoryOAuthStateRepository — 발급 한도와 1회 소비")
    inner class OAuthState {
        private val repository = InMemoryOAuthStateRepository()

        private fun state(
            bch: String = "browser-1",
            nonce: String? = "nonce",
        ) = OAuthAuthorizationState(OAuthProvider.KAKAO, bch, "https://cb", "verifier", nonce, Instant.now())

        @Test
        fun `발급 후 1회만 소비된다`() {
            assertThat(repository.issue("s1", state(), Duration.ofMinutes(5), 5)).isTrue()

            val first = repository.consume("s1", OAuthProvider.KAKAO, "browser-1")
            assertThat(first).isPresent()
            assertThat(repository.consume("s1", OAuthProvider.KAKAO, "browser-1")).isEmpty()
        }

        @Test
        fun `브라우저별 미완료 state 가 한도를 넘으면 발급을 거부한다`() {
            repeat(2) { assertThat(repository.issue("s$it", state(), Duration.ofMinutes(5), 2)).isTrue() }
            assertThat(repository.issue("s-over", state(), Duration.ofMinutes(5), 2)).isFalse()
        }

        /** 불일치 요청이 정상 state 를 소모하면 안 된다 — 소비되지 않고 남아야 한다. */
        @Test
        fun `provider 가 다르면 소비되지 않고 state 도 남는다`() {
            repository.issue("s1", state(), Duration.ofMinutes(5), 5)

            assertThat(repository.consume("s1", OAuthProvider.GOOGLE, "browser-1")).isEmpty()
            assertThat(repository.consume("s1", OAuthProvider.KAKAO, "browser-1")).isPresent()
        }

        @Test
        fun `브라우저 귀속값이 다르면 소비되지 않고 state 도 남는다`() {
            repository.issue("s1", state(), Duration.ofMinutes(5), 5)

            assertThat(repository.consume("s1", OAuthProvider.KAKAO, "other-browser")).isEmpty()
            assertThat(repository.consume("s1", OAuthProvider.KAKAO, "browser-1")).isPresent()
        }

        @Test
        fun `만료된 state 는 소비되지 않는다`() {
            repository.issue("s1", state(), Duration.ofMillis(-1), 5)
            assertThat(repository.consume("s1", OAuthProvider.KAKAO, "browser-1")).isEmpty()
        }

        @Test
        fun `만료된 state 는 브라우저 한도 계산에서 빠진다`() {
            repository.issue("old", state(), Duration.ofMillis(-1), 5)
            assertThat(repository.issue("new", state(), Duration.ofMinutes(5), 1)).isTrue()
        }

        @Test
        fun `없는 state 는 empty 다`() {
            assertThat(repository.consume("missing", OAuthProvider.KAKAO, "browser-1")).isEmpty()
        }

        @Test
        fun `oidcNonce 가 null 이어도 발급과 소비가 된다 - 카카오 경로`() {
            repository.issue("s-null", state(nonce = null), Duration.ofMinutes(5), 5)

            val consumed = repository.consume("s-null", OAuthProvider.KAKAO, "browser-1")
            assertThat(consumed).isPresent()
            assertThat(consumed.get().oidcNonce).isNull()
        }
    }
}
