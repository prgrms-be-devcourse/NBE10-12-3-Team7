package com.dongnemarket.auth.repository

import java.time.Duration
import java.util.Optional

/**
 * 이메일 인증 코드(TTL 데이터) 저장소 추상화. 구현체는 프로파일에 따라 갈린다.
 *
 * `test` → [InMemoryEmailVerificationCodeRepository](외부 인프라 불필요)
 *
 * 그 외(dev/prod) → [RedisEmailVerificationCodeRepository](TTL 기반, `auth:email:verify:{email}`)
 *
 * 전환 규칙 — 파라미터를 nullable 로 둔 이유는 원본 Java 시그니처가 전부 참조형이기 때문이다.
 * non-null 로 조여도 descriptor 는 그대로지만, Kotlin 이 구현체에 런타임 null 검사를 넣어
 * 아직 Java 인 호출부의 동작이 조용히 달라질 수 있다(1단계 `oidcNonce` 사례).
 * `Optional` 반환도 그대로 둔다 — Java 서비스가 `Optional` API 를 직접 쓴다.
 */
interface EmailVerificationCodeRepository {
    /** 코드를 저장한다(이미 있으면 덮어쓰기 — 재요청 시 새 코드로 교체). */
    fun save(
        email: String?,
        code: String?,
        ttl: Duration?,
    )

    fun findCode(email: String?): Optional<String>

    /** 남은 TTL. 코드가 없으면 empty. 쿨다운(재요청 제한) 판단에 쓰인다. */
    fun getRemainingTtl(email: String?): Optional<Duration>

    /** 인증 성공 시 호출: 코드를 삭제한다. */
    fun delete(email: String?)
}
