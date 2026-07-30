package com.dongnemarket.auth.dto

import jakarta.validation.constraints.NotBlank

/**
 * data class 아님(원본에 equals/hashCode 없음).
 *
 * 원본에는 public 생성자가 없고 `protected` 무인자 생성자만 있었다(Jackson 이 필드로 채움).
 * Kotlin final class 는 protected 생성자를 가질 수 없어 주 생성자를 쓰며, 그 결과 public 2-인자
 * 생성자가 **추가**된다. 기존 시그니처를 없애거나 바꾸지 않는 순수 추가라 호출부 영향은 없다.
 */
class OAuthLoginRequest(
    @field:NotBlank
    val code: String?,
    @field:NotBlank
    val state: String?,
)
