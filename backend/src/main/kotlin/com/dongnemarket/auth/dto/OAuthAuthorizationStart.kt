package com.dongnemarket.auth.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 프론트가 그대로 리다이렉트할 수 있는 완성된 인가 URL과 발급된 state, state 만료까지 남은 시간.
 *
 * `@JvmRecord` 로 두어 Java 호출부의 record 접근자를 그대로 유지한다.
 * 참조형 컴포넌트는 원본 Java record 와 동일하게 nullable 로 둔다.
 *
 * [expiresInSeconds] 의 `@get:Schema(requiredMode = NOT_REQUIRED)` 는 OpenAPI 문서 계약을 원본과
 * 맞추기 위한 것이다. springdoc 은 **Kotlin 의 non-null 타입을 자동으로 `required` 로 승격**하는데,
 * 원본 Java 의 primitive `long` 은 required 가 아니었다. 런타임 동작과는 무관하며 문서만 정렬한다.
 */
@JvmRecord
data class OAuthAuthorizationStart(
    val authorizationUrl: String?,
    val state: String?,
    @get:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val expiresInSeconds: Long,
)
