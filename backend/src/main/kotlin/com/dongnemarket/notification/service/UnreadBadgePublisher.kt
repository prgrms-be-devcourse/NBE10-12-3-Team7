package com.dongnemarket.notification.service

import com.dongnemarket.notification.dto.UnreadBadgeSignal
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * 안읽음 배지 갱신 신호를 특정 회원의 per-user 큐로 push 하는 단일 지점.
 *
 * 채팅·댓글·가격 등 unread 를 늘리는 모든 트리거가 이 컴포넌트를 거쳐 신호를 보낸다(WS 전송 로직 일원화).
 * 전송은 `convertAndSendToUser`로 이뤄지며, 브로커가 CONNECT 때 심은 principal(=memberId) 세션에만
 * 라우팅하므로 회원은 어느 화면에 있든 `/user/queue/notifications` 하나만 구독하면 된다.
 * user destination 은 세션 스코프라 도청이 불가능하다(반드시 `/user/queue`, per-user 토픽 금지).
 * 미접속 회원에게 보내도 SimpleBroker 가 no-op 처리하므로 예외 없이 best-effort 로 동작한다.
 */
@Component
class UnreadBadgePublisher(
    private val messagingTemplate: SimpMessagingTemplate,
) {
    /** 주어진 회원에게 "안읽음이 바뀌었다"는 신호를 push 한다(정확한 카운트는 FE 가 재조회). */
    fun pushTo(memberId: Long) {
        messagingTemplate.convertAndSendToUser(
            memberId.toString(),
            USER_QUEUE_DESTINATION,
            UnreadBadgeSignal.changed(),
        )
    }

    companion object {
        /** `convertAndSendToUser`가 `/user/{memberId}/queue/notifications`로 변환하는 목적지. */
        private const val USER_QUEUE_DESTINATION = "/queue/notifications"
    }
}
