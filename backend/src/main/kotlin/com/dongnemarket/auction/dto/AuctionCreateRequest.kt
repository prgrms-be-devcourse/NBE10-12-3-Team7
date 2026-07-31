package com.dongnemarket.auction.dto

import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 경매 등록 요청.
 *
 * 검증 대상 필드(`title`·`startPrice`·`endAt`)를 nullable 로 둔 것은 의도적이다.
 * non-null 로 두면 값이 빠진 요청에서 jackson-module-kotlin 이 역직렬화 단계에서 먼저 터져
 * `@NotBlank`/`@NotNull` 메시지가 나갈 기회가 없다(둘 다 400 이지만 응답 본문이 다르다).
 *
 * `imageUrl`·`description` 은 선택 항목이라 애초에 nullable 이다 —
 * AuctionControllerTest 가 이 두 필드를 생략한 JSON 을 보낸다.
 *
 * 검증 어노테이션은 `@field:` 로 붙인다. 생략하면 PARAMETER 가 먼저 선택되어
 * 검증이 걸리지 않을 수 있다(AuctionCreateRequestValidationTest 로 실측).
 */
data class AuctionCreateRequest(
    @field:NotBlank(message = "제목은 필수입니다.")
    val title: String?,
    val imageUrl: String?, // 선택
    val description: String?, // 선택
    @field:NotNull(message = "시작가는 필수입니다.")
    @field:Positive(message = "시작가는 0보다 커야 합니다.")
    val startPrice: BigDecimal?,
    @field:NotNull(message = "종료 시각은 필수입니다.")
    @field:Future(message = "종료 시각은 미래여야 합니다.")
    val endAt: LocalDateTime?,
)
