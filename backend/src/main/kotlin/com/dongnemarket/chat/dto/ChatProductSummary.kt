package com.dongnemarket.chat.dto

import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import java.math.BigDecimal

/** 채팅방 목록에 표시할 상품 요약(대표사진·제목·가격·거래상태). viewCount 등 목록에 불필요한 필드는 담지 않는다. */
@ConsistentCopyVisibility
data class ChatProductSummary private constructor(
    val productId: Long,
    val title: String,
    val price: BigDecimal,
    val tradeStatus: TradeStatus,
    val thumbnailUrl: String?,
) {
    companion object {
        @JvmStatic
        fun from(product: Product): ChatProductSummary =
            ChatProductSummary(
                product.id,
                product.title,
                product.price,
                product.tradeStatus,
                product.thumbnailUrl,
            )
    }
}
