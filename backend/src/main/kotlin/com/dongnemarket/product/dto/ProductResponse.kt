package com.dongnemarket.product.dto

import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import java.math.BigDecimal

/**
 * 상품 상세. 목록용 [ProductSummaryResponse] 에 설명·판매자 닉네임·이미지 목록이 더해진다.
 *
 * `hidden` 프로퍼티명과 nullability 판단 근거는 [ProductSummaryResponse] 와 같다.
 *
 * `from` 에 `@JvmOverloads` 를 붙이는 이유: 원본 Java 는 1인자·2인자 오버로드를 따로 뒀고
 * 아직 Java 인 `ProductService` 가 둘 다 호출한다. 기본 인자만 주면 Java 에서 1인자 호출이
 * 보이지 않는다.
 */
@ConsistentCopyVisibility
data class ProductResponse private constructor(
    val productId: Long?,
    val memberId: Long?,
    val sellerNickname: String,
    val categoryId: Long?,
    val title: String,
    val description: String,
    val price: BigDecimal,
    val tradeStatus: TradeStatus,
    val regionCode: String,
    val regionName: String,
    val regionFullName: String,
    val viewCount: Long,
    val favoriteCount: Int,
    val thumbnailUrl: String?,
    val imageUrls: List<String>,
    val hidden: Boolean,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun from(
            product: Product,
            imageUrls: List<String> = emptyList(),
        ): ProductResponse =
            ProductResponse(
                product.id,
                product.member.id,
                product.member.displayNickname,
                product.category.id,
                product.title,
                product.description,
                product.price,
                product.tradeStatus,
                product.regionCode,
                product.regionName,
                product.regionFullName,
                product.viewCount,
                product.favoriteCount,
                product.thumbnailUrl,
                imageUrls,
                product.isHidden,
            )
    }
}
