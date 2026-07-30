package com.dongnemarket.global.common.event;

/**
 * 채팅 메시지가 전송될 때 발행되는 도메인 이벤트.
 * <p>notification 도메인이 수신하여 수신자(상대방)의 안읽음 배지를 실시간 갱신하도록 신호를 push한다.
 * 채팅 알림은 저장하지 않고 안읽은 방에서 파생하므로, 이 이벤트는 알림을 저장하지 않고 <b>배지 갱신 신호만</b> 유발한다.
 * 메시지 저장 커밋 이후({@code AFTER_COMMIT})에 처리되어 재조회 시 새 메시지가 이미 반영돼 있다.
 *
 * @param recipientId 배지를 갱신할 수신자 = 메시지를 받은 상대방(발신자의 opponent)
 */
public record ChatMessageSentEvent(Long recipientId) {
}
