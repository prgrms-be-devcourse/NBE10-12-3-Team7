package com.dongnemarket.chat.dto;

/**
 * 읽음 영수증. 방 참여자가 메시지를 읽으면 방 서브토픽({@code /topic/chat-rooms/{roomId}/read})으로
 * 상대에게 push해, 발신자 화면의 "읽음" 표시를 실시간 갱신한다.
 * <p>{@code readerId}로 누가 읽었는지, {@code lastReadMessageId}로 어디까지 읽었는지 알려준다.
 * 구독자는 {@code readerId}가 자기 자신이면 무시하고, 상대라면 자신이 보낸 메시지 중
 * {@code id <= lastReadMessageId}인 것을 읽음으로 표시한다.
 */
public class ChatReadReceiptResponse {

    private final Long roomId;
    private final Long readerId;
    private final Long lastReadMessageId;

    private ChatReadReceiptResponse(Long roomId, Long readerId, Long lastReadMessageId) {
        this.roomId = roomId;
        this.readerId = readerId;
        this.lastReadMessageId = lastReadMessageId;
    }

    public static ChatReadReceiptResponse of(Long roomId, Long readerId, Long lastReadMessageId) {
        return new ChatReadReceiptResponse(roomId, readerId, lastReadMessageId);
    }

    public Long getRoomId() { return roomId; }
    public Long getReaderId() { return readerId; }
    public Long getLastReadMessageId() { return lastReadMessageId; }
}
