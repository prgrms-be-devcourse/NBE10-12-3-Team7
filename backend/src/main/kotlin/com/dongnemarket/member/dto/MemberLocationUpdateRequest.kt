package com.dongnemarket.member.dto

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/**
 * data class 가 아니다 — 원본 Java 클래스에 equals/hashCode 가 없어서 값 기반 동등성이 새로 생기면 안 된다.
 *
 * - 컨테이너 검증(`@NotEmpty`·`@Size`)은 `@field:` 필수 — 생략하면 생성자 파라미터에 붙어
 *   Bean Validation 이 읽지 못한다.
 * - 리스트 자체는 nullable — 원본 계약 "regionCodes:null → 400 INVALID_INPUT_VALUE" 를
 *   검증 단계에서 그대로 유지한다(non-null 로 조이면 역직렬화 500).
 *
 * ## ⚠️ 원소 검증 3중 선언 — 반드시 함께 수정할 것
 *
 * 원소 단위 계약("공백 원소 금지")이 **세 곳에 나뉘어 선언**되어 있다. Kotlin 이 TYPE_USE
 * 어노테이션을 바이트코드에 내보내지 않아(`-Xemit-jvm-type-annotations` 미적용 — 전역 옵션이라
 * 타 도메인 동작까지 바꾸므로 쓰지 않는다) 역할이 분리된 것이다. **원소 제약을 바꿀 때는 셋을
 * 동시에 갱신해야 한다** — 하나만 고치면 소스 의도·런타임 검증·문서가 조용히 어긋난다.
 *
 * | 선언 | 역할 |
 * |---|---|
 * | `List<@NotBlank String>` (타입 인자) | 원래 계약과 소스 의도 표기. **런타임에는 동작하지 않는다** |
 * | `@field:NotBlankElements` | 실제 런타임 검증 — 원소별 위반·경로·메시지 (Java 원본과 동일) |
 * | `@field:ArraySchema(...)` | OpenAPI `items.minLength: 1` — springdoc 이 TYPE_USE 를 못 읽어 수동 선언 |
 *
 * 회귀는 MemberLocationUpdateRequestValidationTest 가 위반 개수·인덱스·경로·메시지 단위로 고정한다.
 */
open class MemberLocationUpdateRequest(
    @field:NotEmpty(message = "동네는 1개 이상 설정해야 합니다.")
    @field:Size(max = 2, message = "동네는 최대 2개까지 설정할 수 있습니다.")
    @field:NotBlankElements(message = "동네 코드는 공백일 수 없습니다.")
    @field:ArraySchema(schema = Schema(implementation = String::class, minLength = 1))
    open val regionCodes: List<
        @NotBlank(message = "동네 코드는 공백일 수 없습니다.")
        String,
    >?,
) {
    /** 원본 `protected MemberLocationUpdateRequest()` 복원. */
    protected constructor() : this(null)
}
