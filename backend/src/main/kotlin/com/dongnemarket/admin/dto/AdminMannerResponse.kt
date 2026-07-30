package com.dongnemarket.admin.dto

import com.dongnemarket.manner.entity.MannerScore
import java.math.BigDecimal

/** 관리자 저신뢰 회원 모니터링 응답. */
@ConsistentCopyVisibility
data class AdminMannerResponse private constructor(
    val memberId: Long?,
    val nickname: String,
    val email: String,
    val score: BigDecimal,
) {
    companion object {
        @JvmStatic
        fun from(mannerScore: MannerScore): AdminMannerResponse =
            AdminMannerResponse(
                mannerScore.member.id,
                mannerScore.member.nickname,
                mannerScore.member.email,
                mannerScore.score,
            )
    }
}
