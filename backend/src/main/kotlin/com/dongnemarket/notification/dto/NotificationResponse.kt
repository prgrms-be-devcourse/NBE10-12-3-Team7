package com.dongnemarket.notification.dto

import com.dongnemarket.notification.entity.Notification
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDateTime

/**
 * 알림 피드 아이템 응답. 저장형(댓글)과 파생형(채팅)을 하나의 shape 으로 노출한다(엔티티 직접 노출 금지).
 *
 * `roomId`는 채팅 알림에서만 채워지며(방 이동용), 댓글 알림에선 null 이라 직렬화에서 생략된다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ConsistentCopyVisibility
data class NotificationResponse private constructor(
    val type: NotificationFeedType,
    val message: String,
    val productId: Long,
    // 채팅 알림에서만 세팅(해당 방으로 이동). 댓글 알림은 null.
    val roomId: Long?,
    // boolean 프로퍼티의 기본 직렬화 키("read") 대신 설계상 명시 키 "isRead"로 노출한다.
    // Kotlin 의 is-접두사 프로퍼티가 Jackson 에서 조용히 rename 되는 것을 @get:JsonProperty 로 고정한다.
    @get:JsonProperty("isRead")
    val isRead: Boolean,
    // 최근 발생 시각(정렬 기준). 댓글은 코얼레싱된 마지막 발생 시각, 채팅은 마지막 메시지 시각.
    val occurredAt: LocalDateTime,
) {
    companion object {
        /** 저장된 알림(댓글·가격변경) → 피드 아이템. 저장 타입을 동일 이름의 피드 타입으로 매핑한다. */
        @JvmStatic
        fun from(notification: Notification): NotificationResponse =
            NotificationResponse(
                NotificationFeedType.valueOf(notification.type.name),
                notification.message,
                notification.productId,
                null,
                notification.isRead,
                notification.lastNotifiedAt,
            )

        /** 안읽은 채팅방에서 파생한 채팅 알림 → 피드 아이템(저장 안 함, 항상 안읽음). */
        @JvmStatic
        fun chat(
            message: String,
            productId: Long,
            roomId: Long,
            occurredAt: LocalDateTime,
        ): NotificationResponse = NotificationResponse(NotificationFeedType.CHAT, message, productId, roomId, false, occurredAt)
    }
}
