package com.dongnemarket.trade.dto

import com.dongnemarket.product.entity.Product
import java.math.BigDecimal
import java.time.LocalDateTime

/** 판매내역 한 건. 거래완료된 내 상품 기준이라 구매자 정보는 담지 않는다(스키마상 별도 구매자 필드가 없음). */
@ConsistentCopyVisibility
data class TradeSaleResponse private constructor(
    val productId: Long?,
    val title: String,
    val thumbnailUrl: String?,
    val price: BigDecimal,
    val regionCode: String?,
    val regionName: String?,
    val regionFullName: String?,
    val completedAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(product: Product): TradeSaleResponse =
            TradeSaleResponse(
                product.id,
                product.title,
                product.thumbnailUrl,
                product.price,
                product.regionCode,
                product.regionName,
                product.regionFullName,
                product.completedAt,
            )
    }
}
