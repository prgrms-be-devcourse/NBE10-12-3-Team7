package com.dongnemarket.chat.dto

/**
 * 메시지 커서 페이지네이션 응답.
 *
 * messages는 최신순(id DESC). nextCursor는 다음(더 오래된) 페이지 요청에 그대로 넘길 커서(가장 오래된 메시지 id),
 * 더 없으면 null. hasNext=false면 과거 메시지가 더 없다.
 */
@ConsistentCopyVisibility
data class ChatMessagePageResponse private constructor(
    val messages: List<ChatMessageResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
) {
    companion object {
        @JvmStatic
        fun of(
            messages: List<ChatMessageResponse>,
            nextCursor: Long?,
            hasNext: Boolean,
        ): ChatMessagePageResponse = ChatMessagePageResponse(messages, nextCursor, hasNext)
    }
}
