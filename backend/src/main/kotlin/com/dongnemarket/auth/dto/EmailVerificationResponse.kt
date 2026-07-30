package com.dongnemarket.auth.dto

import java.time.LocalDateTime

/** 원본은 public 생성자 + getter 만 있는 일반 클래스다. data class 아님. */
class EmailVerificationResponse(
    val email: String?,
    val expiresAt: LocalDateTime?,
)
