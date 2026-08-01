package com.dongnemarket.notification.dto

/** 안읽은 알림 총 개수(댓글 안읽음 + 안읽은 채팅방 수). 헤더 배지용. */
@ConsistentCopyVisibility
data class NotificationUnreadCountResponse private constructor(
    val unreadCount: Long,
) {
    companion object {
        @JvmStatic
        fun of(unreadCount: Long): NotificationUnreadCountResponse = NotificationUnreadCountResponse(unreadCount)
    }
}
