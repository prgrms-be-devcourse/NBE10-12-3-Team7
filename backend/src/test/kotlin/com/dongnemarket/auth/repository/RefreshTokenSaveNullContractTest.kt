package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.RefreshToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

/**
 * `save(null)` 이 **원본 Java 와 같은 지점에서, 외부 저장소에 쓰기 전에** 실패하는지 고정한다.
 *
 * 원본(`9c5ec8f`)의 흐름 — Java 는 수신자를 먼저 평가한 뒤 인자를 평가한다.
 *
 * | 구현 | 실패 전 실제로 호출되는 것 | 첫 역참조 | 쓰기 |
 * |---|---|---|---|
 * | `JpaRefreshTokenRepository` | 없음(`jpaRepository` 는 필드) | `refreshToken.getMemberId()` → **NPE** | `findByMemberId`·`save` **미호출** |
 * | `RedisRefreshTokenRepository` | **`opsForValue()` 1회**(수신자 평가) | `key(refreshToken.getMemberId())` → **NPE** | `set` **미호출** |
 *
 * Kotlin 에서 파라미터를 non-null 로 조이면 **메서드 진입 시점에 null 검사가 삽입**돼
 * Redis 쪽의 `opsForValue()` 호출조차 사라진다 — 그래서 nullable 로 두고 첫 역참조 위치에 `!!` 를 뒀다.
 */
class RefreshTokenSaveNullContractTest {
    @Nested
    @DisplayName("JpaRefreshTokenRepository.save(null)")
    inner class Jpa {
        private val jpaRepository = mock(RefreshTokenJpaEntityRepository::class.java)
        private val repository = JpaRefreshTokenRepository(jpaRepository)

        @Test
        fun `NPE 계열로 실패한다`() {
            val thrown = runCatching { repository.save(null) }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(NullPointerException::class.java)
        }

        /** 원본은 저장소를 한 번도 건드리지 않는다 — 조회도 저장도 없어야 한다. */
        @Test
        fun `저장소 조회와 저장이 모두 일어나지 않는다`() {
            runCatching { repository.save(null) }

            verifyNoInteractions(jpaRepository)
        }
    }

    @Nested
    @DisplayName("RedisRefreshTokenRepository.save(null)")
    inner class Redis {
        private val redisTemplate = mock(StringRedisTemplate::class.java)
        private val valueOperations = mock(ValueOperations::class.java) as ValueOperations<String, String>
        private val repository = RedisRefreshTokenRepository(redisTemplate, 3600L)

        @Test
        fun `NPE 계열로 실패한다`() {
            org.mockito.Mockito
                .`when`(redisTemplate.opsForValue())
                .thenReturn(valueOperations)

            val thrown = runCatching { repository.save(null) }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(NullPointerException::class.java)
        }

        /**
         * 원본 Java 는 수신자(`opsForValue()`)를 먼저 평가한 뒤 인자에서 NPE 가 났다.
         * 그 호출 여부까지 원본과 맞춘다 — 대신 **Redis 쓰기(`set`)는 일어나지 않아야 한다.**
         */
        @Test
        fun `opsForValue 는 호출되지만 SET 은 실행되지 않는다`() {
            org.mockito.Mockito
                .`when`(redisTemplate.opsForValue())
                .thenReturn(valueOperations)

            runCatching { repository.save(null) }

            verify(redisTemplate).opsForValue()
            verifyNoMoreInteractions(redisTemplate)
            verifyNoInteractions(valueOperations)
        }

        @Test
        fun `정상 토큰은 그대로 저장되고 반환된다`() {
            org.mockito.Mockito
                .`when`(redisTemplate.opsForValue())
                .thenReturn(valueOperations)
            val token = RefreshToken.issue(1L, "test-refresh-token", java.time.LocalDateTime.now())

            val saved = repository.save(token)

            assertThat(saved).isSameAs(token)
            verify(valueOperations).set(
                "auth:refresh:1",
                "test-refresh-token",
                java.time.Duration.ofSeconds(3600L),
            )
        }
    }
}
