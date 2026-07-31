package com.dongnemarket.chat.dto

/**
 * 읽음 영수증. 방 참여자가 메시지를 읽으면 방 서브토픽(`/topic/chat-rooms/{roomId}/read`)으로
 * 상대에게 push해, 발신자 화면의 "읽음" 표시를 실시간 갱신한다.
 *
 * `readerId`로 누가 읽었는지, `lastReadMessageId`로 어디까지 읽었는지 알려준다.
 * 구독자는 `readerId`가 자기 자신이면 무시하고, 상대라면 자신이 보낸 메시지 중
 * `id <= lastReadMessageId`인 것을 읽음으로 표시한다.
 */
@ConsistentCopyVisibility
data class ChatReadReceiptResponse private constructor(
    val roomId: Long,
    val readerId: Long,
    val lastReadMessageId: Long,
) {
    companion object {
        @JvmStatic
        fun of(
            roomId: Long,
            readerId: Long,
            lastReadMessageId: Long,
        ): ChatReadReceiptResponse = ChatReadReceiptResponse(roomId, readerId, lastReadMessageId)
    }
}
