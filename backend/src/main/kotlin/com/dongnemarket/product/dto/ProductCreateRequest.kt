package com.dongnemarket.product.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.math.BigDecimal

/**
 * 상품 등록 요청.
 *
 * 검증 어노테이션에 `@field:` 가 필수인 이유: `@NotEmpty`·`@Size` 는 `@Target` 에 PARAMETER 를
 * 포함해서 use-site 를 생략하면 Kotlin 이 생성자 파라미터에 붙인다. 그러면 Bean Validation 이
 * 읽지 못해 **검증이 조용히 사라진다** — 컴파일도 기동도 되고, 이미지 0장·6장이 그대로 등록된다.
 * JPA 어노테이션은 PARAMETER 를 허용하지 않아 자동으로 field 가 되지만 검증은 다르다(PR #31).
 *
 * 원소의 `@NotBlank` 는 TYPE_USE 라 제네릭 인자에 그대로 붙인다.
 *
 * `data class` 가 아닌 이유: Jackson 역직렬화용 무인자 생성자가 필요하고 원본도 `protected`
 * 무인자 생성자를 뒀다. 기본 인자를 주면 jackson-module-kotlin 이 처리한다.
 */
class ProductCreateRequest(
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
