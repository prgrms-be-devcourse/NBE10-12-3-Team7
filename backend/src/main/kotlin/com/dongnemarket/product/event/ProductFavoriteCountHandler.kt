package com.dongnemarket.product.event

import com.dongnemarket.global.common.event.FavoriteAddedEvent
import com.dongnemarket.global.common.event.FavoriteRemovedEvent
import com.dongnemarket.product.repository.ProductRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** 찜 추가·삭제 이벤트를 받아 `Product.favoriteCount` 를 벌크 update 로 증감시킨다. */
@Component
class ProductFavoriteCountHandler(
    private val productRepository: ProductRepository,
) {
    @EventListener
    fun on(event: FavoriteAddedEvent) {
        productRepository.incrementFavoriteCount(event.productId)
    }

    @EventListener
    fun on(event: FavoriteRemovedEvent) {
        productRepository.decrementFavoriteCount(event.productId)
    }
}
