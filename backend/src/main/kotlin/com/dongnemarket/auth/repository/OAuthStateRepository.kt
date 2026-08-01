package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.OAuthProvider
import java.time.Duration
import java.util.Optional

/**
 * OAuth state(+PKCE code_verifier, 브라우저 귀속값, OIDC nonce) 저장소 추상화.
 *
 * `test` → [InMemoryOAuthStateRepository]
 *
 * 그 외(dev/prod) → [RedisOAuthStateRepository](`auth:oauth:state:{state}` Hash +
 * `auth:oauth:bcid:{browserCorrelationHash}:states` ZSET, Lua 로 원자 처리)
 *
 * 전환 규칙 — [issue] 의 `maxPendingPerBrowser` 는 원본이 primitive `int` 라 non-null `Int` 로 둔다.
 * 참조형이던 나머지 파라미터만 nullable 이다.
 */
interface OAuthStateRepository {
    /**
     * state 를 발급한다. 같은 브라우저(browserCorrelationHash)에 이미 [maxPendingPerBrowser] 개
     * 이상의 만료 전 state 가 있으면 발급을 거부한다(false 반환) — "발급 시 만료 항목 제거 → 개수 확인 →
     * state 저장 → 브라우저별 인덱스 추가"가 하나의 원자 연산으로 처리된다.
     */
    fun issue(
        state: String?,
        value: OAuthAuthorizationState,
        ttl: Duration?,
        maxPendingPerBrowser: Int,
    ): Boolean

    /**
     * state 를 검증하고 즉시 소비(삭제)한다. [provider] 와 [browserCorrelationHash] 가
     * 저장된 값과 일치할 때만 원자적으로 삭제 후 반환한다 — 검증에 실패한 요청이 정상 state 를
     * 소모하는 일이 없어야 한다. 존재하지 않거나 만료됐거나 불일치하면 [Optional.empty].
     * 소비 시 브라우저별 인덱스(ZSET)에서도 함께 제거된다.
     */
    fun consume(
        state: String?,
        provider: OAuthProvider?,
        browserCorrelationHash: String?,
    ): Optional<OAuthAuthorizationState>
}
