package com.dongnemarket.chat.dto

import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import java.math.BigDecimal

/**
 * 채팅방 입장 화면에 표시할 상품 상세(대표사진·제목·상세설명·가격·거래상태·판매지역).
 *
 * 필드가 nullable 인 이유: 이 DTO 는 어떤 Kotlin 호출부도 non-null 을 강제하지 않는 순수 직렬화용이고
 * (non-null 이 필요한 목록용은 [ChatProductSummary]), ChatService 단위 테스트가 최소 스텁 Product(mock)로
 * 매핑 무결성만 검증하므로 그 null 필드를 그대로 수용한다. Java 원본과 동일한 관대함이며, 실제 저장 경로에선
 * 상품 필드가 모두 채워진다. nullability 조이기는 전환 완료 후 별도 패스에서 한다.
 */
@ConsistentCopyVisibility
data class ChatProductDetail private constructor(
    val productId: Long?,
    val title: String?,
    val description: String?,
    val price: BigDecimal?,
    val tradeStatus: TradeStatus?,
    val regionCode: String?,
    val regionName: String?,
    val regionFullName: String?,
    val thumbnailUrl: String?,
) {
    companion object {
        @JvmStatic
        fun from(product: Product): ChatProductDetail =
            ChatProductDetail(
                product.id,
                product.title,
                product.description,
                product.price,
                product.tradeStatus,
                product.regionCode,
                product.regionName,
                product.regionFullName,
                product.thumbnailUrl,
            )
    }
}
