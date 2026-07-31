package com.dongnemarket.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

/**
 * data class 아님(원본에 equals/hashCode 없음).
 *
 * `open class` · `open val` · `protected constructor()` 로 원본 Java 의 JVM 표면을 맞춘다.
 * 근거는 `docs/kotlin-migration/auth-migration-notes.md` 「보호 생성자」절.
 */
open class EmailVerificationConfirmRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    open val email: String?,
    @field:NotBlank(message = "인증 코드는 필수입니다.")
    open val code: String?,
) {
    /** 원본 `protected EmailVerificationConfirmRequest()` 복원. 무인자 생성 시 필드 상태(String=null)를 그대로 재현한다. */
    protected constructor() : this(null, null)
}
