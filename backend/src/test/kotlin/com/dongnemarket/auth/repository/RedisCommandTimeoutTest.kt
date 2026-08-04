package com.dongnemarket.auth.repository

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant

/**
 * `spring.data.redis.timeout` 설정이 실제로 적용되는지 검증한다. 이 값이 없으면 Lettuce 기본 커맨드
 * 타임아웃(1분)이 적용돼, Redis 장애 시 로그인·재발급·로그아웃 요청이 하나당 1분씩 걸려 톰캣 스레드풀을
 * 고갈시킬 수 있다(수동 테스트로 실제 확인한 문제). Docker가 필요해 `./gradlew integrationTest`로만 실행한다.
 */
@DataRedisTest(properties = ["spring.data.redis.timeout=2000ms"])
@Tag("integration")
@Testcontainers
class RedisCommandTimeoutTest {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Test
    @DisplayName("Redis 연결이 끊긴 뒤 명령을 보내면, 설정한 타임아웃(2초) 안팎에서 예외로 실패한다 — Lettuce 기본값(1분)까지 붙잡히지 않는다")
    fun commandAfterConnectionLost_failsFastRatherThanHangingOneMinute() {
        redisTemplate.opsForValue().set("warmup", "ok")
        REDIS.stop()

        val start = Instant.now()
        assertThatThrownBy { redisTemplate.opsForValue().get("warmup") }
            .isInstanceOf(DataAccessException::class.java)
        val elapsed = Duration.between(start, Instant.now())

        // 설정값(2초) + 재연결 시도 여유를 감안해도, 미설정 시의 기본값(60초)에 비하면 훨씬 짧게 끝나야 한다.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(15))
    }

    companion object {
        @Container
        val REDIS: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { REDIS.host }
            registry.add("spring.data.redis.port") { REDIS.getMappedPort(6379) }
        }
    }
}
