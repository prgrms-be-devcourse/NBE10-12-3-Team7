package com.dongnemarket.auction.dto

import com.dongnemarket.auction.entity.Auction
import com.dongnemarket.auction.entity.AuctionStatus
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 경매 상세·목록 응답.
 *
 * `auctionId` 는 Auction.id 가, `createdAt` 은 BaseTimeEntity.createdAt 이 nullable 이라
 * 컴파일러가 nullable 을 강제한다(둘 다 저장 전에는 값이 없다).
 */
@ConsistentCopyVisibility
data class AuctionResponse private constructor(
    val auctionId: Long?,
    val sellerId: Long,
    val title: String,
    val imageUrl: String?,
    val description: String?,
    val currentPrice: BigDecimal,
    val highestBidderId: Long?,
    val status: AuctionStatus,
    val endAt: LocalDateTime,
    val createdAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(a: Auction): AuctionResponse =
            AuctionResponse(
                a.id,
                a.sellerId,
                a.title,
                a.imageUrl,
                a.description,
                a.currentPrice,
                a.highestBidderId,
                a.status,
                a.endAt,
                a.createdAt,
            )
    }
}
