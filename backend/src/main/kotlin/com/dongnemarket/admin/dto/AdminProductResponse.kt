package com.dongnemarket.admin.dto

import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 관리자용 상품 응답. 관리 목적상 hidden·deletedAt·createdAt 까지 포함한다.
 *
 * 프로퍼티 이름을 `isHidden` 이 아니라 `hidden` 으로 둔 이유: `hidden` 이면 getter 가 `getHidden()` 이
 * 되어 Jackson 이 JSON 필드를 `hidden` 으로 내보낸다(Java 원본의 `isHidden()` 과 같은 결과).
 * `isHidden` 으로 지으면 jackson-module-kotlin 이 Kotlin 프로퍼티명을 그대로 써서
 * JSON 필드가 `isHidden` 으로 바뀔 수 있다. 이 DTO 의 `isHidden()` 을 호출하는 Java 코드는 없다.
 */
@ConsistentCopyVisibility
data class AdminProductResponse private constructor(
    val productId: Long?,
    val memberId: Long?,
    val categoryId: Long?,
    val title: String,
    val description: String,
    val price: BigDecimal,
    val tradeStatus: TradeStatus,
    val regionCode: String?,
    val regionName: String?,
    val regionFullName: String?,
    val viewCount: Long,
    val hidden: Boolean,
    val deletedAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(product: Product): AdminProductResponse =
            AdminProductResponse(
                product.id,
                product.member.id,
                product.category.id,
                product.title,
                product.description,
                product.price,
                product.tradeStatus,
                // Product.getRegionCode/Name/FullName 은 regionRef 가 null 이면 null 을 반환한다 → String?
                product.regionCode,
                product.regionName,
                product.regionFullName,
                product.viewCount,
                // Java 의 isHidden() 은 Kotlin 에서 isHidden 프로퍼티로 읽는다(is 로 시작해도 합성 프로퍼티).
                product.isHidden,
                product.deletedAt,
                product.createdAt,
            )
    }
}
