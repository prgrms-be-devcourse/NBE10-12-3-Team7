package com.dongnemarket.manner.controller

import com.dongnemarket.global.response.ApiResponse
import com.dongnemarket.manner.dto.MannerRatingCreateRequest
import com.dongnemarket.manner.dto.MannerRatingResponse
import com.dongnemarket.manner.service.MannerRatingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@Tag(name = "MannerRating", description = "거래 후 별점(매너온도 후기) API")
@RestController
class MannerRatingController(
    private val mannerRatingService: MannerRatingService,
) {
    @Operation(summary = "거래 후 별점 등록", description = "완료된 거래에 대해 구매자가 판매자에게 별점(1~5)을 남긴다.")
    @PostMapping("/api/manner/ratings")
    fun rate(
        @AuthenticationPrincipal memberId: Long,
        @Valid @RequestBody request: MannerRatingCreateRequest,
    ): ResponseEntity<ApiResponse<MannerRatingResponse>> {
        val response = mannerRatingService.rate(memberId, request)
        return ResponseEntity.status(201).body(ApiResponse.success(201, "별점이 등록되었습니다.", response))
    }
}
