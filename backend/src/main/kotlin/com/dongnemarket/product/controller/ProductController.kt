package com.dongnemarket.product.controller

import com.dongnemarket.global.response.ApiResponse
import com.dongnemarket.product.dto.ProductCreateRequest
import com.dongnemarket.product.dto.ProductPageResponse
import com.dongnemarket.product.dto.ProductResponse
import com.dongnemarket.product.dto.ProductSearchRequest
import com.dongnemarket.product.dto.ProductStatusUpdateRequest
import com.dongnemarket.product.dto.ProductSummaryResponse
import com.dongnemarket.product.dto.ProductUpdateRequest
import com.dongnemarket.product.service.ProductService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@Tag(name = "Product", description = "상품 API")
@RestController
@RequestMapping("/api/products")
class ProductController(
    private val productService: ProductService,
) {
    @Operation(summary = "상품 등록", description = "로그인한 사용자가 상품을 등록합니다.")
    @PostMapping
    fun createProduct(
        @AuthenticationPrincipal memberId: Long,
        @Valid @RequestBody request: ProductCreateRequest,
    ): ResponseEntity<ApiResponse<ProductResponse>> {
        val response = productService.createProduct(memberId, request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(HttpStatus.CREATED.value(), "상품이 등록되었습니다.", response))
    }

    @Operation(summary = "상품 목록 조회", description = "삭제되거나 숨김 처리되지 않은 상품 목록을 최신 등록순으로 조회합니다.")
    @GetMapping
    fun getProducts(
        @RequestParam(required = false) regionCodes: List<String>?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "30") size: Int,
    ): ApiResponse<ProductPageResponse> = ApiResponse.success(productService.getProducts(regionCodes, cursor, size))

    @Operation(summary = "상품 검색", description = "상품을 키워드, 카테고리, 가격 범위, 거래 상태로 검색합니다.")
    @GetMapping("/search")
    fun searchProducts(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false) minPrice: BigDecimal?,
        @RequestParam(required = false) maxPrice: BigDecimal?,
        @RequestParam(required = false) tradeStatus: String?,
        @RequestParam(required = false) regionCodes: List<String>?,
    ): ApiResponse<List<ProductSummaryResponse>> {
        val request = ProductSearchRequest(keyword, categoryId, minPrice, maxPrice, tradeStatus, regionCodes)
        return ApiResponse.success(productService.searchProducts(request))
    }

    @Operation(summary = "상품 상세 조회", description = "상품 상세 정보를 조회하고 조회수를 1 증가시킵니다.")
    @GetMapping("/{productId}")
    fun getProduct(
        @PathVariable productId: Long,
    ): ApiResponse<ProductResponse> = ApiResponse.success(productService.getProduct(productId))

    @Operation(summary = "상품 수정", description = "작성자가 상품 기본 정보를 수정합니다. 거래완료 상품은 수정할 수 없습니다.")
    @PatchMapping("/{productId}")
    fun updateProduct(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable productId: Long,
        @Valid @RequestBody request: ProductUpdateRequest,
    ): ApiResponse<ProductResponse> = ApiResponse.success(productService.updateProduct(memberId, productId, request))

    @Operation(summary = "상품 삭제", description = "작성자가 상품을 논리 삭제합니다.")
    @DeleteMapping("/{productId}")
    fun deleteProduct(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable productId: Long,
    ): ApiResponse<Void> {
        productService.deleteProduct(memberId, productId)
        return ApiResponse.success()
    }

    @Operation(
        summary = "상품 거래 상태 변경",
        description = "작성자가 상품 거래 상태를 변경합니다. 거래완료 상품은 거래완료 상태만 유지할 수 있습니다.",
    )
    @PatchMapping("/{productId}/status")
    fun updateProductStatus(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable productId: Long,
        @RequestBody request: ProductStatusUpdateRequest,
    ): ApiResponse<ProductResponse> = ApiResponse.success(productService.updateProductStatus(memberId, productId, request))
}
