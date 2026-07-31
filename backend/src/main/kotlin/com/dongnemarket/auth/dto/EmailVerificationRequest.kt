package com.dongnemarket.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

/**
 * data class 아님(원본에 equals/hashCode 없음).
 *
 * `open class` · `open val` · `protected constructor()` 는 원본 Java 의 JVM 표면을 그대로 맞추기 위한 것이다
 * — Java 클래스와 메서드는 기본 non-final 이고, 원본에는 `protected` 무인자 생성자가 있었다.
 * 근거는 `docs/kotlin-migration/auth-migration-notes.md` 「보호 생성자」절.
 */
open class EmailVerificationRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    open val email: String?,
) {
    /** 원본 `protected EmailVerificationRequest()` 복원. Java 무인자 생성자가 남기던 필드 상태(String=null)를 그대로 재현한다. */
    protected constructor() : this(null)
}
