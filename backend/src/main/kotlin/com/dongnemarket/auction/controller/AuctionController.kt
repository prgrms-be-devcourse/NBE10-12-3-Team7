package com.dongnemarket.auction.controller

import com.dongnemarket.auction.dto.AuctionCreateRequest
import com.dongnemarket.auction.dto.AuctionResponse
import com.dongnemarket.auction.service.AuctionService
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Auction", description = "실시간 경매 API")
@RestController
@RequestMapping("/api/auctions")
class AuctionController(
    private val auctionService: AuctionService,
) {
    @Operation(summary = "경매 등록", description = "로그인한 사용자가 판매자가 되어 제목·시작가·종료시각으로 경매를 등록합니다.")
    @PostMapping
    fun create(
        @AuthenticationPrincipal memberId: Long,
        @Valid @RequestBody request: AuctionCreateRequest,
    ): ResponseEntity<ApiResponse<AuctionResponse>> {
        val response = auctionService.create(memberId, request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(HttpStatus.CREATED.value(), "경매가 등록되었습니다.", response))
    }

    @Operation(summary = "경매 목록", description = "등록된 경매를 최신순으로 조회합니다.")
    @GetMapping
    fun getAuctions(): ApiResponse<List<AuctionResponse>> = ApiResponse.success(auctionService.getAuctions())

    @Operation(summary = "경매 상세", description = "경매 하나의 현재 상태(현재가·최고입찰자 등)를 조회합니다.")
    @GetMapping("/{auctionId}")
    fun getAuction(
        @PathVariable auctionId: Long,
    ): ApiResponse<AuctionResponse> = ApiResponse.success(auctionService.getAuction(auctionId))
}
