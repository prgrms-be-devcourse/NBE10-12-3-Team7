package com.dongnemarket.favorite.dto

import com.dongnemarket.favorite.entity.Favorite
import java.time.LocalDateTime

/** 내 관심 목록 응답. 관심 등록 정보 + 상품 요약을 함께 담는다. */
@ConsistentCopyVisibility
data class MyFavoriteResponse private constructor(
    val favoriteId: Long?,
    val createdAt: LocalDateTime?,
    val product: FavoriteProductSummary,
) {
    companion object {
        @JvmStatic
        fun from(favorite: Favorite): MyFavoriteResponse =
            MyFavoriteResponse(
                favorite.id,
                favorite.createdAt,
                FavoriteProductSummary.from(favorite.product),
            )
    }
}
