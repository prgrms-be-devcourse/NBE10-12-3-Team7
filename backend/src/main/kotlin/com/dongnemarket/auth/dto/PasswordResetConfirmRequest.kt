package com.dongnemarket.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * data class 아님(원본에 equals/hashCode 없음).
 *
 * `open class` · `open val` · `protected constructor()` 로 원본 Java 의 JVM 표면을 맞춘다.
 * 근거는 `docs/kotlin-migration/auth-migration-notes.md` 「보호 생성자」절.
 */
open class PasswordResetConfirmRequest(
    @field:NotBlank(message = "토큰은 필수입니다.")
    open val token: String?,
    @field:NotBlank(message = "새 비밀번호는 필수입니다.")
    @field:Size(min = 10, max = 64, message = "비밀번호는 10자 이상 64자 이하로 입력해주세요.")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S+$",
        message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해야 하며 공백을 포함할 수 없습니다.",
    )
    open val newPassword: String?,
) {
    /** 원본 `protected PasswordResetConfirmRequest()` 복원. 무인자 생성 시 필드 상태(String=null)를 그대로 재현한다. */
    protected constructor() : this(null, null)
}
