package com.dongnemarket.favorite.dto

import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import java.math.BigDecimal

/** 관심 목록에 동봉되는 상품 요약(대표사진·이름·가격·판매장소·판매상태). 필요한 필드만 담는 슬림 DTO. */
@ConsistentCopyVisibility
data class FavoriteProductSummary private constructor(
    val productId: Long,
    val categoryId: Long,
    val title: String,
    val price: BigDecimal,
    val regionCode: String,
    val regionName: String,
    val regionFullName: String,
    val tradeStatus: TradeStatus,
    val thumbnailUrl: String?,
) {
    companion object {
        @JvmStatic
        fun from(product: Product): FavoriteProductSummary =
            FavoriteProductSummary(
                product.id,
                product.category.id,
                product.title,
                product.price,
                product.regionCode,
                product.regionName,
                product.regionFullName,
                product.tradeStatus,
                product.thumbnailUrl,
            )
    }
}
