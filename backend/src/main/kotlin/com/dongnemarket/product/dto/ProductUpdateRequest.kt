package com.dongnemarket.product.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.math.BigDecimal

/**
 * 상품 수정 요청. 구조는 [ProductCreateRequest] 와 같다(원본 Java 도 별도 클래스였다).
 *
 * 검증 어노테이션의 `@field:` 가 필수인 이유는 [ProductCreateRequest] 참고 —
 * 생략하면 생성자 파라미터에 붙어 Bean Validation 이 읽지 못하고 검증이 조용히 사라진다.
 */
class ProductUpdateRequest(
    val categoryId: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val price: BigDecimal? = null,
    val regionCode: String? = null,
    @field:NotEmpty(message = "상품 이미지는 1장 이상 등록해야 합니다.")
    @field:Size(max = 5, message = "상품 이미지는 최대 5장까지 등록할 수 있습니다.")
    val imageUrls: List<
        @NotBlank(message = "상품 이미지 URL은 공백일 수 없습니다.")
        String,
    >? = null,
    val thumbnailIndex: Int = 0,
)
