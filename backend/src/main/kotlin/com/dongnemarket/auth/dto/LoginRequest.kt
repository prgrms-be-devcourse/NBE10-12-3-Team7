package com.dongnemarket.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

/**
 * data class 가 아니다 — 원본 Java 클래스에 equals/hashCode 가 없어서 값 기반 동등성이 새로 생기면 안 된다.
 *
 * - `@get:JvmName("isAutoLogin")` — Java 호출부(`AuthController`)의 `isAutoLogin()` 을 그대로 유지한다.
 *   Kotlin 기본 규칙대로면 `getAutoLogin()` 이 되어 호출부가 깨진다. 프로퍼티 이름을 `isAutoLogin` 으로
 *   바꾸는 방법은 쓰지 않는다 — 그러면 JSON 필드명까지 `isAutoLogin` 으로 바뀐다.
 * - `@JvmOverloads` — `autoLogin` 에만 기본값이 있어 (String, String) 오버로드 하나만 추가로 생성된다.
 *   원본의 public 생성자 2개와 정확히 일치한다.
 * - 타입은 전부 nullable — 원본 Java 필드가 null 일 수 있고, non-null 로 조이면 검증(400) 대신
 *   생성 시점 NPE(500)가 난다.
 */
class LoginRequest
    @JvmOverloads
    constructor(
        @field:NotBlank(message = "이메일은 필수입니다.")
        @field:Email(message = "이메일 형식이 올바르지 않습니다.")
        val email: String?,
        @field:NotBlank(message = "비밀번호는 필수입니다.")
        val password: String?,
        /** 자동 로그인 체크 여부. true면 Refresh Token 쿠키를 브라우저 종료 후에도 유지되게 발급하고, false면 세션 쿠키로 발급한다. */
        @get:JvmName("isAutoLogin")
        val autoLogin: Boolean = false,
    )
