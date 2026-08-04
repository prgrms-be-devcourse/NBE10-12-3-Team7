package com.dongnemarket.member.dto

import com.dongnemarket.member.entity.MemberLocation
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 원본과 동일하게 private 생성자 + 정적 팩토리만 노출한다. data class 아님.
 *
 * [active] 프로퍼티에 세 가지 계약이 동시에 걸린다(EmailVerificationConfirmResponse 와 같은 규칙).
 * - Java 호출부의 `isActive()` 게터 → `@get:JvmName`
 * - 응답 JSON 필드명은 `active` → `@get:JsonProperty` (없으면 jackson-module-kotlin 이
 *   Kotlin 프로퍼티 메타데이터 기준으로 이름을 정하고, springdoc 은 `isActive` 팬텀 필드를 만든다)
 * - 원본 primitive `boolean` 은 OpenAPI required 가 아니었다 → `@get:Schema(NOT_REQUIRED)`
 *
 * [sortOrder] 도 원본이 primitive `int` 라 required 가 아니었으므로 NOT_REQUIRED 를 명시한다.
 * [active] 만 `open` 이 아니다 — Kotlin 이 `@JvmName` 을 open 멤버에 금지한다.
 */
open class MemberLocationResponse private constructor(
    open val regionCode: String?,
    open val regionName: String?,
    open val regionFullName: String?,
    @get:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    open val sortOrder: Int,
    @get:JvmName("isActive")
    @get:JsonProperty("active")
    @get:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val active: Boolean,
) {
    companion object {
        @JvmStatic
        fun from(memberLocation: MemberLocation): MemberLocationResponse =
            MemberLocationResponse(
                memberLocation.regionCode,
                memberLocation.regionName,
                memberLocation.regionFullName,
                memberLocation.sortOrder,
                memberLocation.isActive,
            )
    }
}
