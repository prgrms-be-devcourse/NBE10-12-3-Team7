package com.dongnemarket.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

/** data class 아님(원본에 equals/hashCode 없음). */
class EmailVerificationRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    val email: String?,
)
