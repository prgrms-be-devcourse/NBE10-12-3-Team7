package com.dongnemarket.escrow.dto

import jakarta.validation.constraints.NotNull

/**
 * 거래 시작 요청.
 *
 * `productId` 가 nullable 인 것은 의도적이다. non-null 로 두면 필드가 빠진 요청에서
 * jackson-module-kotlin 이 역직렬화 단계에서 먼저 터져 `@NotNull` 메시지가 나갈 기회가 없다.
 * nullable 로 두어야 `@Valid` 가 "상품 ID는 필수입니다." 를 그대로 돌려준다(둘 다 400 이지만 본문이 다르다).
 *
 * 검증 어노테이션은 `@field:` 로 붙인다 — 파라미터에 붙으면 검증이 걸리지 않을 수 있다.
 * 원본의 no-arg 생성자는 제거했다. jackson-module-kotlin 이 주 생성자를 직접 쓴다.
 */
data class EscrowCreateRequest(
    @field:NotNull(message = "상품 ID는 필수입니다.")
    val productId: Long?,
)
