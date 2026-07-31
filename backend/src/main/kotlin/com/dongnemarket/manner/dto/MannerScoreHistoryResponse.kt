package com.dongnemarket.manner.dto

import com.dongnemarket.manner.entity.MannerScoreChangeReason
import com.dongnemarket.manner.entity.MannerScoreHistory
import java.math.BigDecimal
import java.time.LocalDateTime

/** 내정보 페이지의 매너온도 변화 이력 타임라인용 응답. */
@ConsistentCopyVisibility
data class MannerScoreHistoryResponse private constructor(
    val historyId: Long?,
    val changeAmount: BigDecimal,
    val reason: MannerScoreChangeReason,
    val relatedReportId: Long?,
    val createdAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(history: MannerScoreHistory): MannerScoreHistoryResponse =
            MannerScoreHistoryResponse(
                history.id,
                history.changeAmount,
                history.reason,
                history.relatedReportId,
                history.createdAt,
            )
    }
}
