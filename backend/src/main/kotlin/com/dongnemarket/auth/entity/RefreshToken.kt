package com.dongnemarket.auth.entity

import com.dongnemarket.global.common.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Refresh Token 영속 엔티티(`test` 프로파일 경로에서만 DB 에 저장된다 — dev/prod 는 Redis).
 *
 * 전환 규칙 — `docs/kotlin-migration/auth-migration-notes.md` 「영속성 entity」절 참고.
 * - `data class` 가 아니다. 원본에 `equals`/`hashCode` 가 없었고, JPA 엔티티에 값 동등성이 생기면
 *   지연 로딩 프록시·영속성 컨텍스트 동일성과 어긋난다.
 * - 모든 프로퍼티가 nullable 이다. 원본 Java 필드가 전부 참조형(`Long`·`String`·`LocalDateTime`)이라
 *   non-null 로 조이면 `getMemberId()` 의 반환 타입이 `long` 으로 바뀌어 JVM 표면이 깨진다.
 * - setter 는 `protected`. `build.gradle` 의 allOpen 이 프로퍼티까지 open 으로 만들어
 *   Kotlin 이 open 프로퍼티의 private setter 를 금지한다([BaseTimeEntity] 와 동일한 제약).
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken protected constructor() : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Column(name = "member_id", nullable = false, unique = true)
    var memberId: Long? = null
        protected set

    @field:Column(nullable = false, length = 512)
    var token: String? = null
        protected set

    @field:Column(name = "expires_at", nullable = false)
    var expiresAt: LocalDateTime? = null
        protected set

    private constructor(memberId: Long?, token: String?, expiresAt: LocalDateTime?) : this() {
        this.memberId = memberId
        this.token = token
        this.expiresAt = expiresAt
    }

    /** 재로그인/재발급 시 기존 토큰을 새 값으로 교체(회원당 1개 세션 정책) */
    fun replace(
        token: String?,
        expiresAt: LocalDateTime?,
    ) {
        this.token = token
        this.expiresAt = expiresAt
    }

    /**
     * 저장된 토큰 문자열과 일치하는지 확인 (교체되어 폐기된 구 토큰 재사용 방지).
     *
     * 원본은 `this.token.equals(token)` 이라 `token` 컬럼이 null 이면 NPE 였다. 컬럼이 `NOT NULL` 이라
     * 실제로 도달할 수 없는 경로이고, Kotlin 의 `==` 는 같은 값 비교 결과가 동일하므로 그대로 옮겼다.
     */
    fun matches(token: String?): Boolean = this.token == token

    companion object {
        /** 최초 로그인 시 발급 */
        @JvmStatic
        fun issue(
            memberId: Long?,
            token: String?,
            expiresAt: LocalDateTime?,
        ): RefreshToken = RefreshToken(memberId, token, expiresAt)
    }
}
