package com.dongnemarket.trade.controller

import com.dongnemarket.global.response.ApiResponse
import com.dongnemarket.trade.dto.MonthlyTradeStatsResponse
import com.dongnemarket.trade.dto.TradePurchaseResponse
import com.dongnemarket.trade.dto.TradeSaleResponse
import com.dongnemarket.trade.service.TradeService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Trade", description = "거래내역 API")
@RestController
class TradeController(
    private val tradeService: TradeService,
) {
    @Operation(summary = "판매내역 조회", description = "내가 판매자로서 거래완료한 상품 목록을 조회한다.")
    @GetMapping("/api/members/me/trades/sales")
    fun getSales(
        @AuthenticationPrincipal memberId: Long,
    ): ResponseEntity<ApiResponse<List<TradeSaleResponse>>> = ResponseEntity.ok(ApiResponse.success(tradeService.getSales(memberId)))

    @Operation(summary = "구매내역 조회", description = "내가 구매자로서 거래완료한 상품 목록을 조회한다.")
    @GetMapping("/api/members/me/trades/purchases")
    fun getPurchases(
        @AuthenticationPrincipal memberId: Long,
    ): ResponseEntity<ApiResponse<List<TradePurchaseResponse>>> =
        ResponseEntity.ok(ApiResponse.success(tradeService.getPurchases(memberId)))

    @Operation(summary = "월별 거래 통계 조회", description = "월별(yyyy-MM) 판매/구매 건수·금액 통계를 조회한다.")
    @GetMapping("/api/members/me/trades/monthly-stats")
    fun getMonthlyStats(
        @AuthenticationPrincipal memberId: Long,
    ): ResponseEntity<ApiResponse<List<MonthlyTradeStatsResponse>>> =
        ResponseEntity.ok(ApiResponse.success(tradeService.getMonthlyStats(memberId)))
}
