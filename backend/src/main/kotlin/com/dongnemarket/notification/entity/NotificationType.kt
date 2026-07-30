package com.dongnemarket.notification.entity

/**
 * 알림 종류(저장 대상).
 *
 * `COMMENT`(내 상품 새 댓글)와 `PRICE_CHANGE`(구매 관심 상품 가격 변경)를 notifications 테이블에 저장한다.
 * 채팅 알림은 저장하지 않고 조회 시점에 안읽음 방에서 파생하므로(PR-N2) 저장 대상 enum 에 포함하지 않는다.
 */
enum class NotificationType {
    COMMENT,
    PRICE_CHANGE,
}
