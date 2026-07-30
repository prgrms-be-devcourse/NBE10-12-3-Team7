package com.dongnemarket.category.controller

import com.dongnemarket.category.dto.CategoryResponse
import com.dongnemarket.category.service.CategoryService
import com.dongnemarket.global.response.ApiResponse
import com.dongnemarket.product.dto.ProductSummaryResponse
import com.dongnemarket.product.service.ProductService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Category", description = "카테고리 API")
@RestController
@RequestMapping("/api/categories")
class CategoryController(
    private val categoryService: CategoryService,
    private val productService: ProductService,
) {
    @Operation(summary = "카테고리 목록 조회", description = "상품 등록 및 검색에 사용할 카테고리 목록을 조회합니다.")
    @GetMapping
    fun getCategories(): ApiResponse<List<CategoryResponse>> = ApiResponse.success(categoryService.getCategories())

    @Operation(summary = "카테고리별 상품 목록 조회", description = "특정 카테고리의 상품 목록을 최신 등록순으로 조회합니다.")
    @GetMapping("/{categoryId}/products")
    fun getProductsByCategory(
        @PathVariable categoryId: Long,
    ): ApiResponse<List<ProductSummaryResponse>> = ApiResponse.success(productService.getProductsByCategory(categoryId))
}
