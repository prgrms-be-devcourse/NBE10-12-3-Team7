package com.dongnemarket.notification.dto

/**
 * 알림 피드에 노출되는 알림 종류. 응답 전용 타입이다.
 *
 * 저장형(댓글)과 파생형(채팅)을 하나의 피드로 합쳐 내려주기 위해 응답 계층에서만 쓰며,
 * 저장용 [com.dongnemarket.notification.entity.NotificationType](CHAT 미포함)과 분리한다.
 */
enum class NotificationFeedType {
    COMMENT,
    CHAT,
    PRICE_CHANGE,
}
