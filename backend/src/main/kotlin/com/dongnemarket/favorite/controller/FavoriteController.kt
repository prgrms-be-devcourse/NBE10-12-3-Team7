package com.dongnemarket.favorite.controller

import com.dongnemarket.favorite.dto.FavoriteResponse
import com.dongnemarket.favorite.dto.MyFavoriteResponse
import com.dongnemarket.favorite.service.FavoriteService
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Favorite", description = "관심 상품 API")
@RestController
class FavoriteController(
    private val favoriteService: FavoriteService,
) {
    @Operation(summary = "관심 상품 등록", description = "로그인 사용자가 특정 상품을 관심 목록에 등록한다.")
    @PostMapping("/api/products/{productId}/favorites")
    fun addFavorite(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable productId: Long,
    ): ResponseEntity<ApiResponse<FavoriteResponse>> {
        val response = favoriteService.add(memberId, productId)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(HttpStatus.CREATED.value(), "관심 상품으로 등록되었습니다.", response))
    }

    @Operation(
        summary = "내 관심 상품 목록 조회",
        description = "로그인 사용자가 자신이 등록한 관심 상품 목록을 상품 요약과 함께 최근 등록순으로 조회한다. 삭제·숨김 상품은 제외된다.",
    )
    @GetMapping("/api/members/me/favorites")
    fun getMyFavorites(
        @AuthenticationPrincipal memberId: Long,
    ): ResponseEntity<ApiResponse<List<MyFavoriteResponse>>> {
        val response = favoriteService.getMyFavorites(memberId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(summary = "관심 상품 취소", description = "로그인 사용자가 자신이 등록한 관심 상품을 취소한다.")
    @DeleteMapping("/api/products/{productId}/favorites")
    fun removeFavorite(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable productId: Long,
    ): ResponseEntity<ApiResponse<Void?>> {
        favoriteService.remove(memberId, productId)
        return ResponseEntity.ok(ApiResponse.success<Void?>("관심 상품에서 제거되었습니다.", null))
    }
}
