package com.dongnemarket.member.dto

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * 컬렉션의 모든 원소가 blank 가 아님을 검증하는 **필드 레벨** 제약.
 *
 * 원소 단위 `List<@NotBlank String>` 은 TYPE_USE 어노테이션이라 Kotlin 이 기본 설정에서
 * 바이트코드에 내보내지 않는다(`-Xemit-jvm-type-annotations` 필요). 전역 컴파일 옵션은
 * member 외 도메인(product.imageUrls)의 현재 동작까지 바꾸므로, member 계약 복원에는
 * 필드 레벨 제약으로 범위를 한정한다.
 *
 * 위반은 Java 원본(TYPE_USE `@NotBlank`)과 동일하게 **잘못된 원소마다** 생성하고,
 * property path 도 원본과 같은 `필드명[index].<list element>` 형태로 만든다
 * ([ConstraintValidatorContext.ConstraintViolationBuilder.addContainerElementNode]).
 *
 * null 리스트·빈 리스트는 유효로 본다 — 리스트 자체의 필수 여부는 `@NotEmpty` 소관이라
 * 이 제약이 중복 위반을 만들지 않는다(Bean Validation 관례이자 Java 원본과 동일).
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [NotBlankElementsValidator::class])
annotation class NotBlankElements(
    val message: String = "원소는 공백일 수 없습니다.",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class NotBlankElementsValidator : ConstraintValidator<NotBlankElements, List<String?>> {
    override fun isValid(
        value: List<String?>?,
        context: ConstraintValidatorContext,
    ): Boolean {
        if (value == null) {
            return true
        }

        var valid = true
        context.disableDefaultConstraintViolation()
        value.forEachIndexed { index, element ->
            if (element.isNullOrBlank()) {
                valid = false
                context
                    .buildConstraintViolationWithTemplate(context.defaultConstraintMessageTemplate)
                    .addContainerElementNode("<list element>", List::class.java, 0)
                    .inIterable()
                    .atIndex(index)
                    .addConstraintViolation()
            }
        }
        return valid
    }
}
