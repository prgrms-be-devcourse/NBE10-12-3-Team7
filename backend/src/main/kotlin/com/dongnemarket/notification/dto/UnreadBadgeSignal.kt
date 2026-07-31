package com.dongnemarket.notification.dto

/**
 * 안읽음 배지 갱신 신호. per-user 큐(`/user/queue/notifications`)로 push 된다.
 *
 * 정확한 카운트를 담지 않고 **"바뀌었다"는 신호만** 보낸다: 수신 측(FE)이 기존
 * `GET /api/notifications/unread-count` 를 재조회해 배지 숫자를 갱신한다
 * (카운트 계산을 push 경로에 재구현하지 않고 단일 진실 공급원을 재사용, 드리프트 없음).
 */
@ConsistentCopyVisibility
data class UnreadBadgeSignal private constructor(
    val type: String,
) {
    companion object {
        private const val UNREAD_CHANGED = "UNREAD_CHANGED"

        @JvmStatic
        fun changed(): UnreadBadgeSignal = UnreadBadgeSignal(UNREAD_CHANGED)
    }
}
