package com.dongnemarket.member.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * data class 가 아니다 — 원본 Java 클래스에 equals/hashCode 가 없어서 값 기반 동등성이 새로 생기면 안 된다.
 * 그 외 규칙(`@field:` 검증, nullable, `open`/protected 생성자)은 [MemberUpdateRequest] 와 동일하다.
 */
open class PasswordChangeRequest(
    @field:NotBlank(message = "현재 비밀번호는 필수입니다.")
    open val currentPassword: String?,
    @field:NotBlank(message = "새 비밀번호는 필수입니다.")
    @field:Size(min = 10, max = 64, message = "비밀번호는 10자 이상 64자 이하로 입력해주세요.")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S+$",
        message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해야 하며 공백을 포함할 수 없습니다.",
    )
    open val newPassword: String?,
) {
    /** 원본 `protected PasswordChangeRequest()` 복원. */
    protected constructor() : this(null, null)
}
