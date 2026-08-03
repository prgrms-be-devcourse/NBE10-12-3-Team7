package com.dongnemarket.member.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * data class 가 아니다 — 원본 Java 클래스에 equals/hashCode 가 없어서 값 기반 동등성이 새로 생기면 안 된다.
 *
 * - 검증 어노테이션은 `@field:` 필수 — 생략하면 생성자 파라미터에 붙어 Bean Validation 이 읽지 못한다.
 * - 타입은 nullable — 원본 Java 필드가 null 일 수 있고, non-null 로 조이면 검증(400) 대신
 *   생성 시점 NPE(500)가 난다.
 * - `open class` · `open val` · `protected constructor()` — 원본 Java 의 JVM 표면을 그대로 맞춘다.
 */
open class MemberUpdateRequest(
    @field:NotBlank(message = "닉네임은 필수입니다.")
    @field:Size(min = 2, max = 20, message = "닉네임은 2자 이상 20자 이하로 입력해주세요.")
    open val nickname: String?,
) {
    /** 원본 `protected MemberUpdateRequest()` 복원 — 필드 상태(null)도 JVM 기본값 그대로다. */
    protected constructor() : this(null)
}
