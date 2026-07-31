package com.dongnemarket.auction.dto

import com.dongnemarket.auction.entity.Auction
import com.dongnemarket.auction.entity.AuctionStatus
import java.math.BigDecimal
import java.time.LocalDateTime

/** 입찰·종료 시 `/topic/auction/{id}` 로 브로드캐스트하는 경매 현재 상태(현재가·최고입찰자·상태). */
@ConsistentCopyVisibility
data class AuctionStateResponse private constructor(
    val auctionId: Long?,
    val currentPrice: BigDecimal,
    val highestBidderId: Long?,
    val status: AuctionStatus,
    val endAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(auction: Auction): AuctionStateResponse =
            AuctionStateResponse(
                auction.id,
                auction.currentPrice,
                auction.highestBidderId,
                auction.status,
                auction.endAt,
            )
    }
}
