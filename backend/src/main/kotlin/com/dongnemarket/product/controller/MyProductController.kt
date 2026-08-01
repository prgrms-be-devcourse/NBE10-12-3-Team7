package com.dongnemarket.product.controller

import com.dongnemarket.global.response.ApiResponse
import com.dongnemarket.product.dto.ProductSummaryResponse
import com.dongnemarket.product.service.ProductService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Product", description = "상품 API")
@RestController
@RequestMapping("/api/products/me")
class MyProductController(
    private val productService: ProductService,
) {
    /**
     * `memberId` 가 `Long?` 인 이유: [ProductService.getMyProducts] 가 null 을 받아
     * `UNAUTHORIZED` 를 던지는 방어 분기를 갖고 있고 그 분기에 단위 테스트가 있다.
     * 팀의 다른 컨트롤러(chat·manner·auction)는 non-null 을 쓰지만 여기는 원본 시그니처를 보존한다.
     */
    @Operation(summary = "내 상품 목록 조회", description = "로그인한 사용자가 등록한 삭제되지 않은 상품 목록을 최신 등록순으로 조회합니다.")
    @GetMapping
    fun getMyProducts(
        @AuthenticationPrincipal memberId: Long?,
    ): ApiResponse<List<ProductSummaryResponse>> = ApiResponse.success(productService.getMyProducts(memberId))
}
