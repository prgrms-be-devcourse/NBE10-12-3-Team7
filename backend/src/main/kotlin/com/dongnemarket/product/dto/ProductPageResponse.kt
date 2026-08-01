package com.dongnemarket.product.dto

/**
 * 상품 목록 커서 페이지네이션 응답.
 *
 * 프로퍼티명을 `isHasNext` 가 아니라 `hasNext` 로 둔 이유: JSON 필드명이 프론트와의 계약이다.
 * `isHasNext` 로 지으면 jackson-module-kotlin 이 Kotlin 프로퍼티명을 그대로 써서 JSON 이
 * `isHasNext` 로 바뀐다. 대신 Java 게터가 `getHasNext()` 가 되므로 `ProductServiceTest` 의
 * 호출부를 함께 조정했다(단언 대상이 아니라 게터 이름만).
 * chat 의 [com.dongnemarket.chat.dto.ChatMessagePageResponse] 와 같은 방식이다.
 */
@ConsistentCopyVisibility
data class ProductPageResponse private constructor(
    val items: List<ProductSummaryResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
) {
    companion object {
        @JvmStatic
        fun of(
            items: List<ProductSummaryResponse>,
            nextCursor: Long?,
            hasNext: Boolean,
        ): ProductPageResponse = ProductPageResponse(items, nextCursor, hasNext)
    }
}
