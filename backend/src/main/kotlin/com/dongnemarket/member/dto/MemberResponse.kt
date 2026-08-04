package com.dongnemarket.member.dto

import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.entity.Role
import java.time.LocalDateTime

/**
 * 원본과 동일하게 private 생성자 + 정적 팩토리만 노출한다. data class 아님(원본에 equals/hashCode 없음).
 *
 * 타입은 전부 nullable — 원본 Java 필드가 참조형이라 OpenAPI 에서 required 가 아니었고,
 * non-null 로 조이면 springdoc 이 required 로 올려 문서 계약이 바뀐다(SignupResponse 와 같은 규칙).
 *
 * `open class` · `open val` 로 원본의 non-final 클래스·getter 표면을 맞춘다.
 */
open class MemberResponse private constructor(
    open val memberId: Long?,
    open val email: String?,
    open val nickname: String?,
    open val role: Role?,
    open val status: MemberStatus?,
    open val createdAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(member: Member): MemberResponse =
            MemberResponse(
                member.id,
                member.email,
                member.nickname,
                member.role,
                member.status,
                member.createdAt,
            )
    }
}
