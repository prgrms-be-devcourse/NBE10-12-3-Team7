package com.dongnemarket.favorite.dto

import com.dongnemarket.favorite.entity.Favorite
import java.time.LocalDateTime

@ConsistentCopyVisibility
data class FavoriteResponse private constructor(
    val id: Long?,
    val productId: Long,
    val createdAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(favorite: Favorite): FavoriteResponse =
            FavoriteResponse(
                favorite.id,
                favorite.productId,
                favorite.createdAt,
            )
    }
}
