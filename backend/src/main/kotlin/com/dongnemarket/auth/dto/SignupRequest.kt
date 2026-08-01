package com.dongnemarket.auth.dto

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * data class 아님(원본에 equals/hashCode 없음).
 *
 * `@JvmOverloads` 를 붙이지 않는다 — 원본의 public 생성자는 5-인자 하나뿐이라 오버로드가 늘면 안 된다.
 * 두 boolean 의 기본값 `false` 는 JSON 에서 필드가 빠졌을 때 원본(Java 필드 기본값 false)과 같게 만들기 위한 것이다.
 * `@get:JvmName` 으로 `isTermsAgreed()` / `isPersonalInfoCollectionAgreed()` 를 유지한다.
 * `open class` · `open val` · `protected constructor()` 는 원본 Java 의 JVM 표면을 그대로 맞추기 위한 것이다.
 * 근거는 `docs/kotlin-migration/auth-migration-notes.md` 「보호 생성자」절.
 */
open class SignupRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    open val email: String?,
    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 10, max = 64, message = "비밀번호는 10자 이상 64자 이하로 입력해주세요.")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S+$",
        message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해야 하며 공백을 포함할 수 없습니다.",
    )
    open val password: String?,
    @field:NotBlank(message = "닉네임은 필수입니다.")
    @field:Size(min = 2, max = 20, message = "닉네임은 2자 이상 20자 이하로 입력해주세요.")
    open val nickname: String?,
    /**
     * 이용약관 동의 여부. 필수 동의 항목이라 서비스 레벨에서 검증한다(미동의 시 TERMS_NOT_AGREED).
     *
     * 두 boolean 프로퍼티만 `open` 이 아니다 — Kotlin 은 `@JvmName` 을 open 멤버에 붙이지 못한다.
     * `isTermsAgreed()` / `isPersonalInfoCollectionAgreed()` 이름 유지가 getter 오버라이드 가능성보다 우선한다.
     *
     * `@get:JsonProperty` 는 JSON 필드명뿐 아니라 **springdoc OpenAPI schema 이름까지** 고정한다.
     * 없으면 springdoc 이 `isXxx()` getter 를 별도 프로퍼티로 읽어 schema 에 팬텀 필드가 생기고
     * required 로까지 올라간다. 런타임 JSON 은 멀쩡해 기존 테스트로는 잡히지 않는다 —
     * `AuthOpenApiContractTest` 가 이를 고정한다.
     */
    @get:JvmName("isTermsAgreed")
    @get:JsonProperty("termsAgreed")
    @get:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val termsAgreed: Boolean = false,
    /** 개인정보 수집 및 이용 동의 여부. 필수 동의 항목이라 서비스 레벨에서 검증한다(미동의 시 PERSONAL_INFO_COLLECTION_NOT_AGREED). */
    @get:JvmName("isPersonalInfoCollectionAgreed")
    @get:JsonProperty("personalInfoCollectionAgreed")
    @get:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val personalInfoCollectionAgreed: Boolean = false,
) {
    /**
     * 원본 `protected SignupRequest()` 복원. Java 무인자 생성자가 남기던 필드 상태를 그대로 재현한다 —
     * String 필드는 null, primitive boolean 은 false 다(새로 정한 기본값이 아니라 JVM 기본값 그대로).
     */
    protected constructor() : this(null, null, null, false, false)
}
