package com.dongnemarket.product.dto

import jakarta.validation.constraints.Size
import java.math.BigDecimal

/**
 * 상품 검색 조건. 모든 필드가 선택이며 null 이면 그 조건을 쓰지 않는다.
 *
 * `@Size` 에 `@field:` 를 붙이는 이유: 검증 어노테이션은 `@Target` 에 PARAMETER 를 포함해서
 * use-site 를 생략하면 Kotlin 이 생성자 파라미터에 붙인다. 그러면 Bean Validation 이
 * 읽지 못해 **검증이 조용히 사라진다**(컴파일·기동·대부분의 테스트는 그대로 통과).
 * JPA 어노테이션(`@Column` 등)은 PARAMETER 를 허용하지 않아 자동으로 field 가 되지만
 * 검증 어노테이션은 다르다. PR #31 참고.
 *
 * 원본 Java 는 5인자 생성자를 따로 뒀다. 기본 인자 + `@JvmOverloads` 로 옮겨
 * Java 에서 보이는 생성자 2개를 그대로 유지한다.
 */
class ProductSearchRequest
    @JvmOverloads
    constructor(
        val keyword: String? = null,
        val categoryId: Long? = null,
        val minPrice: BigDecimal? = null,
        val maxPrice: BigDecimal? = null,
        val tradeStatus: String? = null,
        @field:Size(max = 2, message = "지역 필터는 최대 2개까지 선택할 수 있습니다.")
        val regionCodes: List<String>? = null,
    )
