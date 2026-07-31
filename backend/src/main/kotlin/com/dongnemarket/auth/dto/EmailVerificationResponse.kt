package com.dongnemarket.auth.dto

import java.time.LocalDateTime

/**
 * 원본은 public 생성자 + getter 만 있는 일반 클래스다. data class 아님.
 * `open class` · `open val` 로 원본의 non-final 클래스·getter 표면을 맞춘다.
 */
open class EmailVerificationResponse(
    open val email: String?,
    open val expiresAt: LocalDateTime?,
)
