package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.RefreshToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime

/**
 * [RedisRefreshTokenRepository]를 실제 Redis(Testcontainers) 위에서 검증한다.
 * Docker가 필요해 기본 `test`에서 제외하고 `./gradlew integrationTest`로만 실행한다.
 *
 * `@ServiceConnection`은 일반 `GenericContainer`에 대해 Redis로 인식되지 않아 컨테이너의
 * 매핑된 포트로 연결을 시도하지 못하고 대기하는 문제가 있어(실제로 겪음), `@DynamicPropertySource`로
 * host/port를 명시적으로 주입한다.
 */
@DataRedisTest
@Tag("integration")
@Testcontainers
class RedisRefreshTokenRepositoryTest {
    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private lateinit var repository: RedisRefreshTokenRepository

    @BeforeEach
    fun setUp() {
        repository = RedisRefreshTokenRepository(redisTemplate, TTL_SECONDS)
    }

    @AfterEach
    fun cleanUp() {
        redisTemplate.connectionFactory!!.connection.serverCommands().flushAll()
    }

    @Test
    @DisplayName("save() 후 findByMemberId()로 같은 토큰을 조회할 수 있고, key는 auth:refresh:{memberId} 형태다")
    fun save_thenFindByMemberId_returnsSameToken() {
        repository.save(RefreshToken.issue(1L, "token-a", LocalDateTime.now()))

        val found = repository.findByMemberId(1L)

        assertThat(found).isPresent()
        assertThat(found.get().token).isEqualTo("token-a")
        assertThat(redisTemplate.hasKey("auth:refresh:1")).isTrue()
    }

    @Test
    @DisplayName("같은 memberId로 다시 save()하면 이전 값을 덮어쓴다(SET 덮어쓰기, insert/update 분기 불필요)")
    fun save_calledTwice_overwritesPreviousToken() {
        repository.save(RefreshToken.issue(2L, "old-token", LocalDateTime.now()))
        repository.save(RefreshToken.issue(2L, "new-token", LocalDateTime.now()))

        val found = repository.findByMemberId(2L)

        assertThat(found).isPresent()
        assertThat(found.get().token).isEqualTo("new-token")
    }

    @Test
    @DisplayName("deleteByMemberId() 이후에는 findByMemberId()가 빈 값을 반환한다")
    fun deleteByMemberId_thenFindByMemberId_returnsEmpty() {
        repository.save(RefreshToken.issue(3L, "token-c", LocalDateTime.now()))

        repository.deleteByMemberId(3L)

        assertThat(repository.findByMemberId(3L)).isEmpty()
    }

    @Test
    @DisplayName("존재하지 않는 memberId를 deleteByMemberId()해도 예외 없이 통과한다(멱등)")
    fun deleteByMemberId_notExisting_doesNotThrow() {
        assertThat(repository.findByMemberId(999L)).isEmpty()

        repository.deleteByMemberId(999L)
    }

    @Test
    @DisplayName("TTL이 지나면 키가 자연 만료되어 findByMemberId()가 빈 값을 반환한다")
    fun save_afterTtlExpires_findByMemberIdReturnsEmpty() {
        repository.save(RefreshToken.issue(4L, "token-d", LocalDateTime.now()))
        assertThat(repository.findByMemberId(4L)).isPresent()

        Thread.sleep((TTL_SECONDS + 1) * 1000)

        assertThat(repository.findByMemberId(4L)).isEmpty()
    }

    @Test
    @DisplayName("save() 시 TTL이 jwt.refresh-token-validity-seconds와 동일하게 설정된다")
    fun save_setsTtlEqualToConfiguredValue() {
        repository.save(RefreshToken.issue(5L, "token-e", LocalDateTime.now()))

        val remaining = redisTemplate.getExpire("auth:refresh:5")

        assertThat(remaining).isNotNull()
        assertThat(remaining).isBetween(0L, TTL_SECONDS)
    }

    companion object {
        @Container
        @JvmStatic
        val REDIS: GenericContainer<*> =
            GenericContainer("redis:7-alpine")
                .withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { REDIS.host }
            registry.add("spring.data.redis.port") { REDIS.getMappedPort(6379) }
        }

        private const val TTL_SECONDS = 2L
    }
}
