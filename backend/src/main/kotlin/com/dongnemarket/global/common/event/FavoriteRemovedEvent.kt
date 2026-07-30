package com.dongnemarket.global.common.event

/**
 * 관심상품 취소 시 발행되는 도메인 이벤트.
 * Product 도메인이 수신하여 해당 상품의 favoriteCount를 1 감소시킨다.
 */
data class FavoriteRemovedEvent(
    val productId: Long,
)
