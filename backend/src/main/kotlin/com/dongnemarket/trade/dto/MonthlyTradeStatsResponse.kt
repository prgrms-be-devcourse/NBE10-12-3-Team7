package com.dongnemarket.trade.dto

import java.math.BigDecimal

/** 월별(yyyy-MM) 거래 통계 한 행. 판매/구매 각각 건수와 금액 합계를 담는다. */
@ConsistentCopyVisibility
data class MonthlyTradeStatsResponse private constructor(
    val yearMonth: String,
    val salesCount: Long,
    val salesAmount: BigDecimal,
    val purchasesCount: Long,
    val purchasesAmount: BigDecimal,
) {
    companion object {
        @JvmStatic
        fun of(
            yearMonth: String,
            salesCount: Long,
            salesAmount: BigDecimal,
            purchasesCount: Long,
            purchasesAmount: BigDecimal,
        ): MonthlyTradeStatsResponse = MonthlyTradeStatsResponse(yearMonth, salesCount, salesAmount, purchasesCount, purchasesAmount)
    }
}
