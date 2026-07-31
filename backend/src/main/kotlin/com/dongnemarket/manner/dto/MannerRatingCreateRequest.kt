package com.dongnemarket.manner.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

data class MannerRatingCreateRequest(
    @field:NotNull(message = "상품 id는 필수입니다.")
    val productId: Long?,
    @field:NotNull(message = "별점은 필수입니다.")
    @field:Min(value = 1, message = "별점은 1~5 사이여야 합니다.")
    @field:Max(value = 5, message = "별점은 1~5 사이여야 합니다.")
    val score: Int?,
)
