package com.dongnemarket.product.dto

import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import java.math.BigDecimal

/**
 * 목록용 상품 요약. 상세 설명·이미지 목록은 담지 않는다.
 *
 * 프로퍼티명을 `isHidden` 이 아니라 `hidden` 으로 둔 이유: JSON 필드명이 프론트와의 계약이다
 * (`ProductControllerTest` 7곳이 `$.data.hidden` 을 검증한다). `isHidden` 으로 지으면
 * jackson-module-kotlin 이 Kotlin 프로퍼티명을 그대로 써서 JSON 이 `isHidden` 으로 바뀐다.
 * admin 의 [com.dongnemarket.admin.dto.AdminProductResponse] 와 같은 방식이다.
 *
 * `viewCount`/`favoriteCount` 는 원본이 primitive(`long`/`int`)라 non-null 로 옮긴다.
 * `productId`/`memberId`/`categoryId` 는 엔티티 id 에서 오므로 `Long?` 다.
 */
@ConsistentCopyVisibility
data class ProductSummaryResponse private constructor(
    val productId: Long?,
    val memberId: Long?,
    val categoryId: Long?,
    val title: String,
    val price: BigDecimal,
    val tradeStatus: TradeStatus,
    val regionCode: String,
    val regionName: String,
    val regionFullName: String,
    val viewCount: Long,
    val favoriteCount: Int,
    val thumbnailUrl: String?,
    val hidden: Boolean,
) {
    companion object {
        @JvmStatic
        fun from(product: Product): ProductSummaryResponse =
            ProductSummaryResponse(
                product.id,
                product.member.id,
                product.category.id,
                product.title,
                product.price,
                product.tradeStatus,
                product.regionCode,
                product.regionName,
                product.regionFullName,
                product.viewCount,
                product.favoriteCount,
                product.thumbnailUrl,
                product.isHidden,
            )
    }
}
