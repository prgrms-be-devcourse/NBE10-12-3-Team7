package com.dongnemarket.auth.dto

import com.dongnemarket.member.entity.Member

/**
 * 원본과 동일하게 private 생성자 + 정적 팩토리만 노출한다. data class 아님.
 *
 * [Member] 는 아직 Java 라 getter 반환이 플랫폼 타입이다 — 원본 시그니처(박싱 `Long`)를 그대로 두고
 * nullability 를 조이지 않는다(조이면 Java 호출부에서 자동 언박싱 NPE 가 난다).
 */
class SignupResponse private constructor(
    val memberId: Long?,
    val email: String?,
    val nickname: String?,
) {
    companion object {
        @JvmStatic
        fun from(member: Member): SignupResponse = SignupResponse(member.id, member.email, member.nickname)
    }
}
