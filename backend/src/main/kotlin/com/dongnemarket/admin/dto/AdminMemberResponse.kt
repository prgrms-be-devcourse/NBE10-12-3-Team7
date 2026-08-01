package com.dongnemarket.admin.dto

import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.entity.Role
import java.time.LocalDateTime

/**
 * 관리자용 회원 응답. 관리 목적상 deletedAt 까지 포함한다.
 */
@ConsistentCopyVisibility
data class AdminMemberResponse private constructor(
    val memberId: Long?,
    val email: String,
    val nickname: String,
    val role: Role,
    val status: MemberStatus,
    val createdAt: LocalDateTime?,
    val deletedAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(member: Member): AdminMemberResponse =
            AdminMemberResponse(
                member.id,
                member.email,
                member.nickname,
                member.role,
                member.status,
                member.createdAt,
                member.deletedAt,
            )
    }
}
