package com.dongnemarket.admin.ai.tool

import com.dongnemarket.admin.dto.AdminProductResponse
import com.dongnemarket.admin.service.AdminProductService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * 상품 조회 Tool (읽기 전용). 기존 AdminProductService에 위임만 한다.
 */
@Component
class AdminProductTools(
    private val adminProductService: AdminProductService,
) {
    @Tool(
        description =
            "전체 상품 목록을 조회한다. 숨김/삭제 여부와 무관하게 모든 상품이 반환된다. " +
                "숨김 상품, 삭제된 상품 등 특정 상태만 필요하면 이 목록에서 걸러서 답하라.",
    )
    fun listProducts(): List<AdminProductResponse> = adminProductService.getProducts()

    @Tool(description = "상품 ID(숫자)로 상품 하나의 상세 정보를 조회한다.")
    fun getProduct(
        @ToolParam(description = "조회할 상품의 ID (양의 정수)") productId: Long,
    ): AdminProductResponse = adminProductService.getProduct(productId)
}
