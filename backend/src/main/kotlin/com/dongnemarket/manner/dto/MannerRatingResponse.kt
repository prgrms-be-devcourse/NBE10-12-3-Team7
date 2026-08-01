package com.dongnemarket.manner.dto

import com.dongnemarket.manner.entity.MannerRating

@ConsistentCopyVisibility
data class MannerRatingResponse private constructor(
    val ratingId: Long?,
    val productId: Long?,
    val rateeId: Long?,
    val score: Int,
) {
    companion object {
        @JvmStatic
        fun from(rating: MannerRating): MannerRatingResponse =
            MannerRatingResponse(
                rating.id,
                rating.product.id,
                rating.ratee.id,
                rating.score,
            )
    }
}
