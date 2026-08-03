package com.dongnemarket.member.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/**
 * data class 가 아니다 — 원본 Java 클래스에 equals/hashCode 가 없어서 값 기반 동등성이 새로 생기면 안 된다.
 *
 * - 컨테이너 검증(`@NotEmpty`·`@Size`)은 `@field:` 필수, 원소의 `@NotBlank` 는 TYPE_USE 라
 *   제네릭 인자에 그대로 붙인다(ProductCreateRequest.imageUrls 와 같은 규칙).
 * - 리스트 자체는 nullable — 원본 계약 "regionCodes:null → 400 INVALID_INPUT_VALUE" 를
 *   검증 단계에서 그대로 유지한다(non-null 로 조이면 역직렬화 500).
 */
open class MemberLocationUpdateRequest(
    @field:NotEmpty(message = "동네는 1개 이상 설정해야 합니다.")
    @field:Size(max = 2, message = "동네는 최대 2개까지 설정할 수 있습니다.")
    open val regionCodes: List<
        @NotBlank(message = "동네 코드는 공백일 수 없습니다.")
        String,
    >?,
) {
    /** 원본 `protected MemberLocationUpdateRequest()` 복원. */
    protected constructor() : this(null)
}
