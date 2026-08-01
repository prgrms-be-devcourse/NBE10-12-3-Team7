package com.dongnemarket.product.dto

/**
 * 거래상태 변경 요청. 문자열로 받아 서비스에서 [com.dongnemarket.product.entity.TradeStatus] 로 변환한다
 * (잘못된 값을 역직렬화 단계가 아니라 도메인 에러로 다루기 위해 원본이 String 이다).
 *
 * `data class` 가 아닌 이유: Jackson 역직렬화를 위해 무인자 생성자가 필요하고, 원본도 public
 * 무인자 생성자를 두고 있었다. 프로퍼티가 `var` 인 것도 같은 이유다.
 */
class ProductStatusUpdateRequest(
    var tradeStatus: String? = null,
)
