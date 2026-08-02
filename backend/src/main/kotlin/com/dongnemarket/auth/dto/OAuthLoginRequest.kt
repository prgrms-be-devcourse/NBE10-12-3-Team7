package com.dongnemarket.auth.dto

import jakarta.validation.constraints.NotBlank

/**
 * data class 아님(원본에 equals/hashCode 없음).
 *
 * 다른 요청 DTO 와 달리 주 생성자 프로퍼티 방식을 쓰지 않고 원본 Java 구조를 그대로 옮겼다.
 * 원본은 **public 생성자가 없고 `protected` 무인자 생성자만** 있었는데(Jackson 이 무인자 생성자로
 * 만든 뒤 private 필드에 직접 값을 넣는다), 주 생성자 프로퍼티로 바꾸면 원본에 없던
 * `public OAuthLoginRequest(String, String)` 이 공개 API 에 추가된다.
 * 순수 언어 전환 PR 에서 공개 표면을 넓히지 않으려고 원본 구조를 유지했다.
 *
 * `protected` 생성자를 두려면 클래스가 상속 가능해야 하므로 `open` 을 붙인다 — 원본 Java 클래스도
 * `final` 이 아니었으므로 이 역시 원본과 같은 표면이다. getter 도 원본처럼 오버라이드 가능하게 `open`.
 */
open class OAuthLoginRequest protected constructor() {
    @field:NotBlank
    private var code: String? = null

    @field:NotBlank
    private var state: String? = null

    open fun getCode(): String? = code

    open fun getState(): String? = state
}
