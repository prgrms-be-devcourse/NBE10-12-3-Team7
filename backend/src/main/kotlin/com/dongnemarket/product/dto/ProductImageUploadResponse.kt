package com.dongnemarket.product.dto

/**
 * `of` 에 `@JvmStatic` 이 필요한 이유: 전환이 진행 중인 지금 `ProductImageStorageService`·
 * `ProductImageController` 가 아직 Java 이고 `ProductImageUploadResponse.of(...)` 로 호출한다.
 */
@ConsistentCopyVisibility
data class ProductImageUploadResponse private constructor(
    val imageUrls: List<String>,
) {
    companion object {
        @JvmStatic
        fun of(imageUrls: List<String>): ProductImageUploadResponse = ProductImageUploadResponse(imageUrls)
    }
}
