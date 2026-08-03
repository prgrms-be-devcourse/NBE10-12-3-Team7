package com.dongnemarket.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.stream.Collectors
import java.util.stream.IntStream

/**
 * 기존 전역 `RateLimitFilter`(`global/filter`, 팀장 관리 영역이라 이 PR에서 수정하지 않음)가
 * 실제 소셜 로그인 엔드포인트(4단계 경로 `/api/auth/oauth/kakao/authorization`)에도 적용되는지,
 * 그리고 Spring Security 인증 처리보다 먼저 동작하는지를 실제 내장 서버로 검증한다.
 *
 * `/api/auth/oauth/kakao/authorization`은 SecurityConfig에서 permitAll이라 인증 실패로는
 * 순서를 증명할 수 없다 — 대신 인증이 필요한 `/api/members/me`를 별도로 호출해 "429가 401보다
 * 먼저 나온다"는 사실로 순서를 증명한다.
 *
 * `test` 프로파일은 `RateLimitFilterConfig`가 `@Profile("!test")`라 아예 등록되지
 * 않으므로 비활성화하고, MySQL/Redis 모두 Testcontainers로 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@Testcontainers
class OAuthEndpointRateLimitOrderTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    private fun url(path: String): String = "http://localhost:" + port + path

    @Test
    @DisplayName("4단계 경로(/api/auth/oauth/kakao/authorization)도 RateLimitFilter(url-pattern /api/*)에 걸린다 — 한도 초과 시 429")
    fun nestedOAuthPath_isCoveredByGlobalRateLimitFilter() {
        // 실제 컨트롤러를 호출한다(state 발급까지 정상 수행됨 — permitAll이라 인증 없이도 200).
        val clientId = "test-client-" + UUID.randomUUID()
        val statuses =
            IntStream
                .range(0, CAPACITY + 2)
                .mapToObj { callAuthorization(clientId) }
                .collect(Collectors.toList())

        val okCount = statuses.stream().filter { s -> s == HttpStatus.OK }.count()
        val tooManyCount = statuses.stream().filter { s -> s.value() == 429 }.count()

        assertThat(okCount).isEqualTo(CAPACITY.toLong())
        assertThat(tooManyCount).isEqualTo(2L)
    }

    @Test
    @DisplayName("인증이 필요한 경로에서 한도 초과 후 응답이 401이 아니라 429라는 사실로, RateLimitFilter가 Spring Security 인증 처리보다 먼저 동작함을 확인한다")
    fun rateLimitFilter_runsBeforeSecurityAuthentication() {
        val clientId = "order-client-" + UUID.randomUUID()
        for (i in 0 until CAPACITY) {
            callProtectedEndpoint(clientId)
        }

        val overLimitStatus = callProtectedEndpoint(clientId)

        assertThat(overLimitStatus.value()).isEqualTo(429)
    }

    /**
     * 보안 취약점 재현 테스트 — 일반 회귀 테스트가 아니다("우회에 성공해야 통과"하는 테스트를 그대로
     * 켜두면 안 됨). `global/filter`는 이 PR의 수정 범위가 아니라 여기서 고치지 않는다.
     *
     * 재현 조건과 영향은 PR 설명 및 팀 전달 보고에 별도로 기재했다. `global/filter` 수정 PR이
     * 나오면, 이 테스트를 "위조 헤더로도 우회되면 안 된다"는 정상 기대값으로 뒤집어 회귀 테스트로 전환할 것.
     */
    @Test
    @Disabled(
        "알려진 보안 이슈(미등록): RateLimitFilter가 X-Forwarded-For 첫 값을 무조건 신뢰해 위조로 우회 가능 — 아직 별도 GitHub 이슈가 생성되지 않았다. global/filter 수정 PR에서 신뢰 경계를 정한 뒤 정상 기대값 테스트로 전환 예정. 재현 자료는 PR 설명 참고. (이슈 생성 시 이 문구에 이슈 번호를 연결할 것)",
    )
    @DisplayName("[보안 재현, 비활성화] X-Forwarded-For를 요청마다 위조하면 같은 클라이언트도 매번 새 한도를 받아 rate limit이 우회된다")
    fun knownIssue_spoofedForwardedFor_bypassesRateLimit() {
        val statuses =
            IntStream
                .range(0, CAPACITY + 2)
                .mapToObj { callAuthorization("spoofed-" + UUID.randomUUID()) }
                .collect(Collectors.toList())

        val anyTooMany = statuses.stream().anyMatch { s -> s.value() == 429 }

        assertThat(anyTooMany).isFalse()
    }

    private fun callAuthorization(forwardedFor: String): HttpStatus =
        callWithForwardedFor(url("/api/auth/oauth/kakao/authorization"), HttpMethod.POST, forwardedFor)

    private fun callProtectedEndpoint(forwardedFor: String): HttpStatus =
        callWithForwardedFor(url("/api/members/me"), HttpMethod.GET, forwardedFor)

    private fun callWithForwardedFor(
        url: String,
        method: HttpMethod,
        forwardedFor: String,
    ): HttpStatus {
        val headers = HttpHeaders()
        headers.set("X-Forwarded-For", forwardedFor)
        val response =
            restTemplate.exchange(
                url,
                method,
                HttpEntity<String>(headers),
                String::class.java,
            )
        return response.statusCode as HttpStatus
    }

    companion object {
        @Container
        @JvmStatic
        val MYSQL: MySQLContainer<*> =
            MySQLContainer<Nothing>("mysql:8.0").apply {
                withDatabaseName("dongne_ratelimit_test")
                withUsername("test")
                withPassword("test")
            }

        @Container
        @JvmStatic
        val REDIS: GenericContainer<*> =
            GenericContainer<Nothing>("redis:7-alpine").apply {
                withExposedPorts(6379)
            }

        private const val CAPACITY = 3

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", MYSQL::getJdbcUrl)
            registry.add("spring.datasource.username", MYSQL::getUsername)
            registry.add("spring.datasource.password", MYSQL::getPassword)
            registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName)
            registry.add("spring.jpa.hibernate.ddl-auto") { "update" }
            registry.add("spring.flyway.enabled") { false }

            registry.add("spring.data.redis.host", REDIS::getHost)
            registry.add("spring.data.redis.port") { REDIS.getMappedPort(6379) }

            registry.add("spring.mail.username") { "test@example.com" }
            registry.add("spring.mail.password") { "test" }

            registry.add("rate-limit.capacity") { CAPACITY }
            registry.add("rate-limit.window-seconds") { 10 }
        }
    }
}
