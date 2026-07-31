package com.dongnemarket.chat.dto

import com.dongnemarket.chat.entity.ChatMessage
import java.time.LocalDateTime

/** 메시지 한 건 응답. senderId로 내 메시지/상대 메시지(좌우 말풍선)를 구분한다. */
@ConsistentCopyVisibility
data class ChatMessageResponse private constructor(
    val messageId: Long?,
    val senderId: Long?,
    val content: String,
    val createdAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(message: ChatMessage): ChatMessageResponse =
            ChatMessageResponse(
                message.id,
                message.senderId,
                message.content,
                message.createdAt,
            )
    }
}
