package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.OAuthProvider
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * Redis 기반 구현체. state 는 Hash(`auth:oauth:state:{state}`)로, 브라우저별 미완료 state
 * 인덱스는 ZSET(`auth:oauth:bcid:{browserCorrelationHash}:states`, score=만료시각 epoch millis)으로 관리한다.
 *
 * 발급(issue)과 소비(consume) 모두 Lua 스크립트로 원자 처리한다 — Redis 는 스크립트 실행 중 다른 명령을
 * 끼워 넣지 않으므로, "조회 후 판단 후 쓰기" 사이에 동시 요청이 끼어들 여지가 없다.
 *
 * 전환 규칙 — **Lua 스크립트 본문·key prefix·ARGV 순서·필드 이름을 한 글자도 바꾸지 않았다.**
 * 하나라도 달라지면 저장 형식이 바뀌어 기능 변경이 된다.
 */
@Repository
@Profile("!test")
class RedisOAuthStateRepository(
    private val redisTemplate: StringRedisTemplate,
) : OAuthStateRepository {
    override fun issue(
        state: String?,
        value: OAuthAuthorizationState,
        ttl: Duration?,
        maxPendingPerBrowser: Int,
    ): Boolean {
        val nowMillis = System.currentTimeMillis()
        val result =
            redisTemplate.execute(
                ISSUE_SCRIPT,
                listOf(stateKey(state), bcidKey(value.browserCorrelationHash)),
                ttl!!.toMillis().toString(),
                nowMillis.toString(),
                maxPendingPerBrowser.toString(),
                state,
                value.provider!!.name,
                value.browserCorrelationHash,
                value.redirectUri,
                value.codeVerifier,
                nullToEmpty(value.oidcNonce),
                value.issuedAt!!.toEpochMilli().toString(),
            )
        return result != null && result == 1L
    }

    @Suppress("UNCHECKED_CAST")
    override fun consume(
        state: String?,
        provider: OAuthProvider?,
        browserCorrelationHash: String?,
    ): Optional<OAuthAuthorizationState> {
        val fields =
            redisTemplate.execute(
                CONSUME_SCRIPT,
                listOf(stateKey(state), bcidKey(browserCorrelationHash)),
                provider!!.name,
                browserCorrelationHash,
                state,
            ) as List<Any?>?
        if (fields == null || fields.isEmpty()) {
            return Optional.empty()
        }
        return Optional.of(toState(fields))
    }

    /**
     * CONSUME_SCRIPT 가 고정 순서로 반환한 배열
     * ([provider, browserCorrelationHash, redirectUri, codeVerifier, oidcNonce, issuedAt])을 값 객체로 되돌린다.
     */
    private fun toState(fields: List<Any?>): OAuthAuthorizationState {
        val oidcNonce = fields[IDX_OIDC_NONCE].toString()
        return OAuthAuthorizationState(
            OAuthProvider.valueOf(fields[IDX_PROVIDER].toString()),
            fields[IDX_BROWSER_CORRELATION_HASH].toString(),
            fields[IDX_REDIRECT_URI].toString(),
            fields[IDX_CODE_VERIFIER].toString(),
            if (oidcNonce.isEmpty()) null else oidcNonce,
            Instant.ofEpochMilli(fields[IDX_ISSUED_AT].toString().toLong()),
        )
    }

    private fun nullToEmpty(value: String?): String = value ?: ""

    private fun stateKey(state: String?): String = STATE_KEY_PREFIX + state

    private fun bcidKey(browserCorrelationHash: String?): String = BCID_KEY_PREFIX + browserCorrelationHash + BCID_KEY_SUFFIX

    companion object {
        private const val STATE_KEY_PREFIX = "auth:oauth:state:"
        private const val BCID_KEY_PREFIX = "auth:oauth:bcid:"
        private const val BCID_KEY_SUFFIX = ":states"

        /**
         * KEYS=[stateKey, bcidZsetKey],
         * ARGV=[ttlMillis, nowMillis, maxPending, state, provider, browserCorrelationHash, redirectUri,
         * codeVerifier, oidcNonce, issuedAtEpochMilli]
         */
        private val ISSUE_SCRIPT: RedisScript<Long> =
            DefaultRedisScript(
                """
                local ttlMillis = tonumber(ARGV[1])
                local nowMillis = tonumber(ARGV[2])
                local maxPending = tonumber(ARGV[3])

                redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', nowMillis)
                local count = redis.call('ZCARD', KEYS[2])
                if count >= maxPending then
                    return 0
                end

                redis.call('HSET', KEYS[1],
                    'provider', ARGV[5],
                    'browserCorrelationHash', ARGV[6],
                    'redirectUri', ARGV[7],
                    'codeVerifier', ARGV[8],
                    'oidcNonce', ARGV[9],
                    'issuedAt', ARGV[10])
                redis.call('PEXPIRE', KEYS[1], ttlMillis)

                redis.call('ZADD', KEYS[2], nowMillis + ttlMillis, ARGV[4])
                redis.call('PEXPIRE', KEYS[2], ttlMillis + 1000)

                return 1
                """.trimIndent() + "\n",
                Long::class.java,
            )

        /**
         * KEYS=[stateKey, bcidZsetKey], ARGV=[expectedProvider, expectedBrowserCorrelationHash, state]
         *
         * `HGETALL` 은 순서를 보장하지 않으므로(구현/버전에 따라 달라질 수 있음), 필요한 필드를
         * 고정된 순서로 `HGET` 해 배열로 반환한다 — 반환값 인덱스가 항상
         * [provider, browserCorrelationHash, redirectUri, codeVerifier, oidcNonce, issuedAt]로 고정된다.
         */
        private val CONSUME_SCRIPT: RedisScript<List<*>> =
            DefaultRedisScript(
                """
                local provider = redis.call('HGET', KEYS[1], 'provider')
                if not provider then
                    return {}
                end
                local bch = redis.call('HGET', KEYS[1], 'browserCorrelationHash')
                if provider ~= ARGV[1] or bch ~= ARGV[2] then
                    return {}
                end

                local redirectUri = redis.call('HGET', KEYS[1], 'redirectUri')
                local codeVerifier = redis.call('HGET', KEYS[1], 'codeVerifier')
                local oidcNonce = redis.call('HGET', KEYS[1], 'oidcNonce')
                local issuedAt = redis.call('HGET', KEYS[1], 'issuedAt')

                redis.call('DEL', KEYS[1])
                redis.call('ZREM', KEYS[2], ARGV[3])

                return {provider, bch, redirectUri, codeVerifier, oidcNonce, issuedAt}
                """.trimIndent() + "\n",
                List::class.java as Class<List<*>>,
            )

        private const val IDX_PROVIDER = 0
        private const val IDX_BROWSER_CORRELATION_HASH = 1
        private const val IDX_REDIRECT_URI = 2
        private const val IDX_CODE_VERIFIER = 3
        private const val IDX_OIDC_NONCE = 4
        private const val IDX_ISSUED_AT = 5
    }
}
