package com.dongnemarket.auth.repository

import java.time.Duration

/**
 * 로그인 실패 횟수 저장소 추상화. 구현체는 프로파일에 따라 갈린다.
 *
 * `test` → [InMemoryLoginAttemptRepository](H2/외부 인프라 불필요)
 *
 * 그 외(dev/prod) → [RedisLoginAttemptRepository](TTL 기반, `auth:login:fail:{email}`)
 *
 * 전환 규칙 — [getFailureCount] 의 반환 타입은 Kotlin `Long`(non-null)이다.
 * 원본이 primitive `long` 이라 여기서는 non-null 이 원본과 같은 descriptor 를 만든다.
 * 참조형이었던 파라미터만 nullable 로 둔다.
 */
interface LoginAttemptRepository {
    /** 이메일의 현재 실패 횟수를 조회한다(윈도우가 지났다면 0). */
    fun getFailureCount(email: String?): Long

    /**
     * 실패 횟수를 1 증가시킨다. 최초 실패(1로 증가) 시에만 [lockWindow] 를 TTL 로 설정해
     * 그 시점부터 고정된 윈도우가 시작되게 한다(윈도우 내 반복 실패는 TTL 을 연장하지 않는다).
     */
    fun incrementFailure(
        email: String?,
        lockWindow: Duration?,
    )

    /** 로그인 성공 시 호출: 실패 횟수를 초기화한다. */
    fun resetFailure(email: String?)
}
