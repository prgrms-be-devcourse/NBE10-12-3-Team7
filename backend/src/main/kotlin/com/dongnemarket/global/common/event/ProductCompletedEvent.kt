package com.dongnemarket.global.common.event

/**
 * 상품 거래 상태가 COMPLETED(거래완료)로 바뀔 때 발행되는 도메인 이벤트.
 *
 * manner 도메인이 구독하여 판매자의 정상 거래 완료 건수를 누적하고, 매너온도 회복 배치의
 * "최근 30일 정상 거래 완료 건수" 판단 근거로 사용한다. 중복 발행 방지를 위해 이미 COMPLETED인
 * 상품을 다시 COMPLETED로 바꾸는 호출에서는 발행되지 않는다(ProductService 측 가드).
 *
 * @property productId 거래완료된 상품 id
 * @property sellerId  판매자(상품 소유자) id
 */
data class ProductCompletedEvent(
    val productId: Long,
    val sellerId: Long,
)
