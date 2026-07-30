package com.dongnemarket.notification.dto;

/**
 * 안읽음 배지 갱신 신호. per-user 큐({@code /user/queue/notifications})로 push된다.
 * <p>정확한 카운트를 담지 않고 <b>"바뀌었다"는 신호만</b> 보낸다: 수신 측(FE)이 기존
 * {@code GET /api/notifications/unread-count} 를 재조회해 배지 숫자를 갱신한다
 * (카운트 계산을 push 경로에 재구현하지 않고 단일 진실 공급원을 재사용, 드리프트 없음).
 */
public record UnreadBadgeSignal(String type) {

    private static final String UNREAD_CHANGED = "UNREAD_CHANGED";

    public static UnreadBadgeSignal changed() {
        return new UnreadBadgeSignal(UNREAD_CHANGED);
    }
}
