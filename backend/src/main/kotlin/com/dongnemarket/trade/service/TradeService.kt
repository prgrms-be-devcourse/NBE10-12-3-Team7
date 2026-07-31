package com.dongnemarket.trade.service

import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.trade.dto.MonthlyTradeStatsResponse
import com.dongnemarket.trade.dto.TradePurchaseResponse
import com.dongnemarket.trade.dto.TradeSaleResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.YearMonth
import java.util.TreeMap

/**
 * 거래내역(판매/구매/월별 통계) 조회. 별도 "거래" 엔티티 없이 Product·ChatRoom을 읽기 전용으로만 참조한다.
 *
 * 판매내역은 내 상품 중 거래완료(COMPLETED)분, 구매내역은 스키마상 구매자를 식별할 수 있는 유일한 경로인
 * 채팅방(내가 buyer인 방) 중 상품이 거래완료된 것을 기준으로 한다 — 채팅 없이 완료 처리된 거래는 구매내역에
 * 잡히지 않는 구조적 한계가 있다.
 */
@Service
@Transactional(readOnly = true)
class TradeService(
    private val productRepository: ProductRepository,
    private val chatRoomRepository: ChatRoomRepository,
) {
    /** 내가 판매자로서 거래완료한 상품 목록(최근 완료순). */
    fun getSales(memberId: Long): List<TradeSaleResponse> = completedSaleProducts(memberId).map { TradeSaleResponse.from(it) }

    /** 내가 구매자로서 거래완료한 상품 목록(최근 완료순). */
    fun getPurchases(memberId: Long): List<TradePurchaseResponse> = completedPurchaseRooms(memberId).map { TradePurchaseResponse.from(it) }

    /** 월별(yyyy-MM) 판매/구매 건수·금액 통계. 거래가 있던 달만 포함하며 최신 달이 먼저 온다. */
    fun getMonthlyStats(memberId: Long): List<MonthlyTradeStatsResponse> {
        val byMonth = TreeMap<YearMonth, MonthlyAgg>(Comparator.reverseOrder())

        for (product in completedSaleProducts(memberId)) {
            val agg = byMonth.getOrPut(YearMonth.from(product.completedAt)) { MonthlyAgg() }
            agg.salesCount++
            agg.salesAmount = agg.salesAmount.add(product.price)
        }
        for (room in completedPurchaseRooms(memberId)) {
            val agg = byMonth.getOrPut(YearMonth.from(room.product.completedAt)) { MonthlyAgg() }
            agg.purchasesCount++
            agg.purchasesAmount = agg.purchasesAmount.add(room.product.price)
        }

        return byMonth.map { (yearMonth, agg) ->
            MonthlyTradeStatsResponse.of(yearMonth.toString(), agg.salesCount, agg.salesAmount, agg.purchasesCount, agg.purchasesAmount)
        }
    }

    private fun completedSaleProducts(memberId: Long): List<Product> =
        productRepository
            .findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(memberId)
            .filter { it.isCompleted }
            .sortedByDescending { it.completedAt }

    private fun completedPurchaseRooms(memberId: Long): List<ChatRoom> =
        chatRoomRepository
            .findMyChatRooms(memberId)
            .filter { it.buyerId == memberId }
            .filter { it.product.isCompleted }
            .sortedByDescending { it.product.completedAt }

    private data class MonthlyAgg(
        var salesCount: Long = 0,
        var salesAmount: BigDecimal = BigDecimal.ZERO,
        var purchasesCount: Long = 0,
        var purchasesAmount: BigDecimal = BigDecimal.ZERO,
    )
}

private val Product.isCompleted: Boolean
    get() = tradeStatus == TradeStatus.COMPLETED
