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
 */
class EmailVerificationConfirmResponse(
    val email: String?,
    @get:JvmName("isVerified")
    @get:JsonProperty("verified")
    val verified: Boolean,
)
