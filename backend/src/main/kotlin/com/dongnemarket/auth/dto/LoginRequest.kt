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
 * - `open class` · `open val` · `protected constructor()` — 원본 Java 의 JVM 표면을 그대로 맞춘다.
 *   근거는 `docs/kotlin-migration/auth-migration-notes.md` 「보호 생성자」절.
 */
open class LoginRequest
    @JvmOverloads
    constructor(
        @field:NotBlank(message = "이메일은 필수입니다.")
        @field:Email(message = "이메일 형식이 올바르지 않습니다.")
        open val email: String?,
        @field:NotBlank(message = "비밀번호는 필수입니다.")
        open val password: String?,
        /**
         * 자동 로그인 체크 여부. true면 Refresh Token 쿠키를 브라우저 종료 후에도 유지되게 발급하고, false면 세션 쿠키로 발급한다.
         *
         * 이 프로퍼티만 `open` 이 아니다 — Kotlin 은 `@JvmName` 을 open 멤버에 붙이지 못한다
         * (이름을 바꾼 getter 를 오버라이드 가능하게 두면 가상 디스패치가 깨지기 때문).
         * `isAutoLogin()` 이름 유지(Java 호출부 + JSON 계약)가 getter 오버라이드 가능성보다 우선한다.
         */
        @get:JvmName("isAutoLogin")
        val autoLogin: Boolean = false,
    ) {
        /**
         * 원본 `protected LoginRequest()` 복원. Java 무인자 생성자가 남기던 필드 상태를 그대로 재현한다 —
         * String 필드는 null, primitive boolean 은 false 다(새로 정한 기본값이 아니라 JVM 기본값 그대로).
         */
        protected constructor() : this(null, null, false)
    }
