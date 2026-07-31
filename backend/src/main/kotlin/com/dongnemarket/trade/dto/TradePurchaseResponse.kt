package com.dongnemarket.trade.dto

import com.dongnemarket.chat.entity.ChatRoom
import java.math.BigDecimal
import java.time.LocalDateTime

/** 구매내역 한 건. 채팅방을 통해서만 "내가 산 상품"을 알 수 있어(스키마상 별도 구매자 필드 없음) ChatRoom 기준으로 만든다. */
@ConsistentCopyVisibility
data class TradePurchaseResponse private constructor(
    val productId: Long?,
    val title: String,
    val thumbnailUrl: String?,
    val price: BigDecimal,
    val sellerNickname: String,
    val roomId: Long?,
    val completedAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(room: ChatRoom): TradePurchaseResponse =
            TradePurchaseResponse(
                room.product.id,
                room.product.title,
                room.product.thumbnailUrl,
                room.product.price,
                room.seller.displayNickname,
                room.id,
                room.product.completedAt,
            )
    }
}
