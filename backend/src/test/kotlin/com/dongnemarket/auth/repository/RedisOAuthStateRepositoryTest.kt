package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.OAuthProvider
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import java.util.stream.IntStream

/**
 * [RedisOAuthStateRepository]를 실제 Redis(Testcontainers) 위에서 검증한다.
 * InMemory 구현([InMemoryOAuthStateRepository])은 `synchronized`로 원자성을 흉내낼 뿐이라,
 * 실제 Lua 스크립트의 원자성(동시 요청 하에서도 발급 한도를 넘지 않는지, 같은 state를 두 번 소비할 수
 * 없는지)은 진짜 Redis로만 확인할 수 있다.
 */
@DataRedisTest
@Tag("integration")
@Testcontainers
class RedisOAuthStateRepositoryTest {
    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private lateinit var repository: RedisOAuthStateRepository

    @BeforeEach
    fun setUp() {
        repository = RedisOAuthStateRepository(redisTemplate)
    }

    @AfterEach
    fun cleanUp() {
        redisTemplate.connectionFactory!!.connection.serverCommands().flushAll()
    }

    private fun sampleState(browserCorrelationHash: String): OAuthAuthorizationState =
        OAuthAuthorizationState(
            OAuthProvider.GOOGLE,
            browserCorrelationHash,
            "https://app.example.com/oauth/google/callback",
            "code-verifier-value",
            "oidc-nonce-value",
            Instant.now(),
        )

    private fun newState(): String = UUID.randomUUID().toString()

    @Test
    @DisplayName("issue() 후 consume()으로 같은 값을 정확히 한 번 돌려받는다")
    fun issue_thenConsume_returnsSameValue() {
        val state = newState()
        val value = sampleState("bcid-1")

        val issued = repository.issue(state, value, TTL, 5)
        val consumed = repository.consume(state, OAuthProvider.GOOGLE, "bcid-1")

        assertThat(issued).isTrue()
        assertThat(consumed).isPresent()
        assertThat(consumed.get().provider).isEqualTo(OAuthProvider.GOOGLE)
        assertThat(consumed.get().browserCorrelationHash).isEqualTo("bcid-1")
        assertThat(consumed.get().redirectUri).isEqualTo(value.redirectUri)
        assertThat(consumed.get().codeVerifier).isEqualTo(value.codeVerifier)
        assertThat(consumed.get().oidcNonce).isEqualTo(value.oidcNonce)
    }

    @Test
    @DisplayName("브라우저당 미완료 state가 한도(maxPendingPerBrowser)에 도달하면 다음 issue()는 거부된다")
    fun issue_upToMaxPending_rejectsNext() {
        val bcid = "bcid-cap"
        repeat(3) {
            assertThat(repository.issue(newState(), sampleState(bcid), TTL, 3)).isTrue()
        }

        val fourth = repository.issue(newState(), sampleState(bcid), TTL, 3)

        assertThat(fourth).isFalse()
    }

    @Test
    @DisplayName("만료된 state는 브라우저별 한도 계산에서 제외된다(발급 시 자동 정리)")
    fun issue_expiredEntriesAreExcludedFromPendingCount() {
        val bcid = "bcid-expiry"
        val shortTtl = Duration.ofSeconds(1)
        repository.issue(newState(), sampleState(bcid), shortTtl, 1)

        Thread.sleep(1500L)

        val afterExpiry = repository.issue(newState(), sampleState(bcid), TTL, 1)

        assertThat(afterExpiry).isTrue()
    }

    @Test
    @DisplayName("provider가 불일치하면 consume()은 빈 값을 반환하고 state를 소비하지 않는다 — 이후 올바른 provider로 다시 소비할 수 있다")
    fun consume_wrongProvider_doesNotConsumeState() {
        val state = newState()
        repository.issue(state, sampleState("bcid-2"), TTL, 5)

        val wrongAttempt = repository.consume(state, OAuthProvider.KAKAO, "bcid-2")
        assertThat(wrongAttempt).isEmpty()

        val correctAttempt = repository.consume(state, OAuthProvider.GOOGLE, "bcid-2")
        assertThat(correctAttempt).isPresent()
    }

    @Test
    @DisplayName("browserCorrelationHash가 불일치하면 consume()은 빈 값을 반환하고 state를 소비하지 않는다")
    fun consume_wrongBrowserCorrelationHash_doesNotConsumeState() {
        val state = newState()
        repository.issue(state, sampleState("bcid-3"), TTL, 5)

        val wrongAttempt = repository.consume(state, OAuthProvider.GOOGLE, "other-browser")
        assertThat(wrongAttempt).isEmpty()

        val correctAttempt = repository.consume(state, OAuthProvider.GOOGLE, "bcid-3")
        assertThat(correctAttempt).isPresent()
    }

    @Test
    @DisplayName("정상 소비 시 state Hash와 브라우저별 ZSET 인덱스가 함께 제거된다")
    fun consume_success_removesHashAndZsetEntry() {
        val state = newState()
        val bcid = "bcid-4"
        repository.issue(state, sampleState(bcid), TTL, 5)

        repository.consume(state, OAuthProvider.GOOGLE, bcid)

        assertThat(redisTemplate.hasKey("auth:oauth:state:$state")).isFalse()
        assertThat(redisTemplate.opsForZSet().zCard("auth:oauth:bcid:$bcid:states")).isEqualTo(0L)
    }

    @Test
    @DisplayName("issue() 직후 state Hash와 ZSET 모두 TTL이 설정되어 있다")
    fun issue_setsTtlOnBothKeys() {
        val state = newState()
        val bcid = "bcid-5"

        repository.issue(state, sampleState(bcid), TTL, 5)

        val stateTtl = redisTemplate.getExpire("auth:oauth:state:$state")
        val bcidTtl = redisTemplate.getExpire("auth:oauth:bcid:$bcid:states")

        assertThat(stateTtl).isNotNull().isGreaterThan(0L)
        assertThat(bcidTtl).isNotNull().isGreaterThan(0L)
    }

    @Test
    @DisplayName("동일 state를 동시에 소비하려는 여러 요청 중 정확히 하나만 성공한다(원자적 검증+삭제)")
    fun concurrentConsume_sameState_onlyOneSucceeds() {
        val state = newState()
        val bcid = "bcid-race"
        repository.issue(state, sampleState(bcid), TTL, 10)

        val attempts = 20
        val executor = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)

        val tasks =
            IntStream
                .range(0, attempts)
                .mapToObj<Callable<Boolean>> {
                    Callable {
                        ready.countDown()
                        start.await()
                        repository.consume(state, OAuthProvider.GOOGLE, bcid).isPresent
                    }
                }.collect(Collectors.toList())

        val futures = tasks.stream().map { executor.submit(it) }.collect(Collectors.toList())
        ready.await(5, TimeUnit.SECONDS)
        start.countDown()

        val successCount =
            futures
                .stream()
                .mapToLong { f ->
                    try {
                        if (f.get()) 1L else 0L
                    } catch (e: Exception) {
                        throw RuntimeException(e)
                    }
                }.sum()
        executor.shutdown()

        assertThat(successCount).isEqualTo(1L)
    }

    @Test
    @DisplayName("동시 issue() 요청이 한도를 넘어도 발급 성공 개수는 정확히 maxPendingPerBrowser를 넘지 않는다")
    fun concurrentIssue_neverExceedsMaxPending() {
        val bcid = "bcid-concurrent-issue"
        val maxPending = 5
        val attempts = 30
        val executor = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val successCount = AtomicInteger()

        val tasks =
            IntStream
                .range(0, attempts)
                .mapToObj<Callable<Void>> {
                    Callable {
                        ready.countDown()
                        start.await()
                        if (repository.issue(newState(), sampleState(bcid), TTL, maxPending)) {
                            successCount.incrementAndGet()
                        }
                        null
                    }
                }.collect(Collectors.toList())

        val futures = tasks.stream().map { executor.submit(it) }.collect(Collectors.toList())
        ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        for (f in futures) {
            f.get(5, TimeUnit.SECONDS)
        }
        executor.shutdown()

        assertThat(successCount.get()).isEqualTo(maxPending)
        val finalPending = redisTemplate.opsForZSet().zCard("auth:oauth:bcid:$bcid:states")
        assertThat(finalPending).isEqualTo(maxPending.toLong())
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

        private val TTL = Duration.ofSeconds(5)
    }
}
