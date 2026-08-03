package com.dongnemarket.auth.repository

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
import java.time.Duration

/**
 * [RedisEmailVerificationCodeRepository]를 실제 Redis(Testcontainers) 위에서 검증한다.
 * Docker가 필요해 기본 `test`에서 제외하고 `./gradlew integrationTest`로만 실행한다.
 */
@DataRedisTest
@Tag("integration")
@Testcontainers
class RedisEmailVerificationCodeRepositoryTest {
    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private lateinit var repository: RedisEmailVerificationCodeRepository

    @BeforeEach
    fun setUp() {
        repository = RedisEmailVerificationCodeRepository(redisTemplate)
    }

    @AfterEach
    fun cleanUp() {
        redisTemplate.connectionFactory!!.connection.serverCommands().flushAll()
    }

    @Test
    @DisplayName("save() 후 findCode()로 같은 코드를 조회할 수 있고, key는 auth:email:verify:{email} 형태다")
    fun save_thenFindCode_returnsSameCode() {
        repository.save("a@example.com", "123456", TTL)

        assertThat(repository.findCode("a@example.com")).contains("123456")
        assertThat(redisTemplate.hasKey("auth:email:verify:a@example.com")).isTrue()
    }

    @Test
    @DisplayName("같은 이메일로 다시 save()하면(재요청) 이전 코드를 덮어쓴다")
    fun save_calledTwice_overwritesPreviousCode() {
        repository.save("b@example.com", "111111", TTL)
        repository.save("b@example.com", "222222", TTL)

        assertThat(repository.findCode("b@example.com")).contains("222222")
    }

    @Test
    @DisplayName("delete() 이후에는 findCode()가 빈 값을 반환한다")
    fun delete_thenFindCode_returnsEmpty() {
        repository.save("c@example.com", "333333", TTL)

        repository.delete("c@example.com")

        assertThat(repository.findCode("c@example.com")).isEmpty()
    }

    @Test
    @DisplayName("코드가 없으면 getRemainingTtl()은 빈 값을 반환한다")
    fun getRemainingTtl_noCode_returnsEmpty() {
        assertThat(repository.getRemainingTtl("never-requested@example.com")).isEmpty()
    }

    @Test
    @DisplayName("save() 직후 getRemainingTtl()은 설정한 TTL 이하의 값을 반환한다")
    fun getRemainingTtl_afterSave_returnsWithinConfiguredTtl() {
        repository.save("d@example.com", "444444", TTL)

        assertThat(repository.getRemainingTtl("d@example.com"))
            .isPresent()
            .hasValueSatisfying { remaining -> assertThat(remaining).isLessThanOrEqualTo(TTL).isPositive() }
    }

    @Test
    @DisplayName("TTL이 지나면 코드가 자연 만료되어 findCode()가 빈 값을 반환한다")
    fun save_afterTtlExpires_findCodeReturnsEmpty() {
        repository.save("e@example.com", "555555", TTL)
        assertThat(repository.findCode("e@example.com")).isPresent()

        Thread.sleep(TTL.plusSeconds(1).toMillis())

        assertThat(repository.findCode("e@example.com")).isEmpty()
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

        private val TTL = Duration.ofSeconds(2)
    }
}
