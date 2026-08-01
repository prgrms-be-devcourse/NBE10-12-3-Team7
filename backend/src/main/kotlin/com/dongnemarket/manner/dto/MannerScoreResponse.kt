package com.dongnemarket.manner.dto

import com.dongnemarket.manner.entity.MannerScore
import java.math.BigDecimal

/** 상품 상세·채팅방 등에서 노출하는 공개 매너온도 요약. */
@ConsistentCopyVisibility
data class MannerScoreResponse private constructor(
    val memberId: Long?,
    val score: BigDecimal,
) {
    companion object {
        @JvmStatic
        fun from(mannerScore: MannerScore): MannerScoreResponse = MannerScoreResponse(mannerScore.member.id, mannerScore.score)
    }
}
