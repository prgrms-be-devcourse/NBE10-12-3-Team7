package com.dongnemarket.admin.controller

import com.dongnemarket.admin.dto.AdminProductResponse
import com.dongnemarket.admin.service.AdminProductService
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin - Product", description = "관리자 상품 관리 API")
@RestController
@RequestMapping("/api/admin/products")
class AdminProductController(
    private val adminProductService: AdminProductService,
) {
    @Operation(summary = "상품 목록 조회", description = "관리자가 숨김·삭제 여부와 무관하게 전체 상품을 조회한다.")
    @GetMapping
    fun getProducts(): ResponseEntity<ApiResponse<List<AdminProductResponse>>> =
        ResponseEntity.ok(ApiResponse.success(adminProductService.getProducts()))

    @Operation(summary = "상품 상세 조회", description = "관리자가 상품 단건 정보를 조회한다.")
    @GetMapping("/{productId}")
    fun getProduct(
        @PathVariable productId: Long,
    ): ResponseEntity<ApiResponse<AdminProductResponse>> = ResponseEntity.ok(ApiResponse.success(adminProductService.getProduct(productId)))

    @Operation(summary = "상품 숨김", description = "관리자가 상품을 숨김 처리한다(작성자가 아니어도 가능).")
    @PatchMapping("/{productId}/hidden")
    fun hideProduct(
        @PathVariable productId: Long,
    ): ResponseEntity<ApiResponse<Void>> {
        adminProductService.hideProduct(productId)
        return ResponseEntity.ok(ApiResponse.success())
    }

    @Operation(summary = "상품 삭제", description = "관리자가 상품을 소프트 삭제한다.")
    @DeleteMapping("/{productId}")
    fun deleteProduct(
        @PathVariable productId: Long,
    ): ResponseEntity<ApiResponse<Void>> {
        adminProductService.deleteProduct(productId)
        return ResponseEntity.ok(ApiResponse.success())
    }
}
