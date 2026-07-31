package com.dongnemarket.auction.dto

import java.math.BigDecimal

/**
 * 입찰 요청(STOMP `@Payload`).
 *
 * `amount` 가 nullable 인 것은 AuctionBidController 가 `amount == null` 을 직접 검사해
 * INVALID_INPUT_VALUE 로 방어하기 때문이다. non-null 로 조이면 그 방어 코드가 죽고
 * 역직렬화 단계에서 다른 예외로 바뀐다(STOMP 라 `@Valid` 가 붙어 있지 않다).
 */
data class BidRequest(
    val amount: BigDecimal?,
)
