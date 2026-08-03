package com.dongnemarket.member.dto

import jakarta.validation.ConstraintViolation
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 원소 단위 검증 계약(공백 원소 금지)이 **런타임에 실제로 동작**하는지 고정하는 회귀 테스트.
 *
 * Kotlin 은 타입 인자 어노테이션(TYPE_USE)을 기본적으로 바이트코드에 내보내지 않아,
 * `List<@NotBlank String>` 이 소스에 있어도 Bean Validation 이 읽지 못한 채 조용히 사라진다
 * (member 전환에서 실제로 발생). 런타임 검증은 `@field:NotBlankElements` 가 담당하며,
 * 이 테스트는 MockMvc 를 거치지 않고 Validator 로 직접 검증해 서비스 계층의 2차 방어에
 * 가려지지 않고 계약 이탈 즉시 실패한다.
 *
 * 위반 개수·인덱스·property path(`regionCodes[i].<list element>`)까지 Java 원본
 * (TYPE_USE `@NotBlank`)과 동일해야 한다.
 */
class MemberLocationUpdateRequestValidationTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    private val elementMessage = "동네 코드는 공백일 수 없습니다."

    private fun elementViolations(vararg codes: String): List<ConstraintViolation<MemberLocationUpdateRequest>> =
        validator
            .validate(MemberLocationUpdateRequest(codes.toList()))
            .filter { it.message == elementMessage }

    @Test
    fun `정상 코드 목록은 위반이 없다`() {
        val violations = validator.validate(MemberLocationUpdateRequest(listOf("1168010100", "1144012400")))

        assertThat(violations).isEmpty()
    }

    @Test
    fun `빈 문자열 원소는 index 0 위반 1개다`() {
        val violations = elementViolations("")

        assertThat(violations).hasSize(1)
        assertThat(violations[0].message).isEqualTo(elementMessage)
        assertThat(violations[0].propertyPath.toString()).isEqualTo("regionCodes[0].<list element>")
    }

    @Test
    fun `공백 문자열 원소는 index 0 위반 1개다`() {
        val violations = elementViolations(" ")

        assertThat(violations).hasSize(1)
        assertThat(violations[0].message).isEqualTo(elementMessage)
        assertThat(violations[0].propertyPath.toString()).isEqualTo("regionCodes[0].<list element>")
    }

    @Test
    fun `정상 코드와 공백이 섞이면 공백 원소의 index 1 위반 1개다`() {
        val violations = elementViolations("1168010100", " ")

        assertThat(violations).hasSize(1)
        assertThat(violations[0].message).isEqualTo(elementMessage)
        assertThat(violations[0].propertyPath.toString()).isEqualTo("regionCodes[1].<list element>")
    }

    @Test
    fun `공백 원소가 여러 개면 원소마다 위반이 생기고 인덱스가 정확하다`() {
        val violations = elementViolations(" ", "")

        assertThat(violations).hasSize(2)
        assertThat(violations.map { it.message }).containsOnly(elementMessage)
        assertThat(violations.map { it.propertyPath.toString() })
            .containsExactlyInAnyOrder(
                "regionCodes[0].<list element>",
                "regionCodes[1].<list element>",
            )
    }

    @Test
    fun `null 목록은 NotEmpty 소관이라 원소 위반을 만들지 않는다`() {
        val violations = validator.validate(MemberLocationUpdateRequest(null))

        assertThat(violations.map { it.message })
            .containsExactly("동네는 1개 이상 설정해야 합니다.")
    }

    @Test
    fun `빈 목록은 NotEmpty 소관이라 원소 위반을 만들지 않는다`() {
        val violations = validator.validate(MemberLocationUpdateRequest(listOf()))

        assertThat(violations.map { it.message })
            .containsExactly("동네는 1개 이상 설정해야 합니다.")
    }
}
