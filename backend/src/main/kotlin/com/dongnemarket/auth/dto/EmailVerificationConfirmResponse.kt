package com.dongnemarket.auth.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * data class 아님(원본에 equals/hashCode 없음).
 *
 * boolean 프로퍼티에 두 가지 제약이 동시에 걸린다.
 * - Java 호출부(`EmailVerificationServiceTest`)가 `isVerified()` 를 호출한다 → `@get:JvmName`
 * - 응답 JSON 필드명은 `verified` 여야 한다 → `@get:JsonProperty`
 *
 * `@get:JvmName` 만 붙이면 jackson-module-kotlin 이 Kotlin 프로퍼티 메타데이터를 기준으로 이름을
 * 정해 JSON 필드가 `verified` 가 아니게 된다(실제로 `$.data.verified` 를 못 찾아 테스트가 깨졌다).
 * 그래서 직렬화 이름을 명시적으로 고정한다.
 *
 * `open class` · `open val email` 로 원본의 non-final 표면을 맞춘다. 다만 [verified] 는 `open` 이
 * 될 수 없다 — Kotlin 이 `@JvmName` 을 open 멤버에 금지한다(이름을 바꾼 getter 를 오버라이드
 * 가능하게 두면 가상 디스패치가 깨진다). `isVerified()` 이름과 JSON 필드명 유지가 우선이다.
 */
open class EmailVerificationConfirmResponse(
    open val email: String?,
    @get:JvmName("isVerified")
    @get:JsonProperty("verified")
    val verified: Boolean,
)
