package com.dongnemarket.auth.dto

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
 */
class SignupRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    val email: String?,
    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 10, max = 64, message = "비밀번호는 10자 이상 64자 이하로 입력해주세요.")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S+$",
        message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해야 하며 공백을 포함할 수 없습니다.",
    )
    val password: String?,
    @field:NotBlank(message = "닉네임은 필수입니다.")
    @field:Size(min = 2, max = 20, message = "닉네임은 2자 이상 20자 이하로 입력해주세요.")
    val nickname: String?,
    /** 이용약관 동의 여부. 필수 동의 항목이라 서비스 레벨에서 검증한다(미동의 시 TERMS_NOT_AGREED). */
    @get:JvmName("isTermsAgreed")
    val termsAgreed: Boolean = false,
    /** 개인정보 수집 및 이용 동의 여부. 필수 동의 항목이라 서비스 레벨에서 검증한다(미동의 시 PERSONAL_INFO_COLLECTION_NOT_AGREED). */
    @get:JvmName("isPersonalInfoCollectionAgreed")
    val personalInfoCollectionAgreed: Boolean = false,
)
