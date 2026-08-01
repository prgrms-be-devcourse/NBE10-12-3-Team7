package com.dongnemarket.chat.entity

import com.dongnemarket.global.common.BaseTimeEntity
import com.dongnemarket.member.entity.Member
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * 채팅 메시지 한 건. 방(ChatRoom)에 속하며 보낸 사람(sender)과 내용을 가진다.
 *
 * 메시지는 방과 달리 계속 쌓이는(성장하는) 데이터라 별도 엔티티로 분리하고, 커서 페이지네이션으로 조회한다.
 * 상품이 삭제·숨김돼도 대화 기록은 보존해야 하므로 상품/방으로부터의 cascade 삭제는 두지 않는다.
 *
 * `data class` 가 아니라 `class`, 필드는 전부 불변이며 인스턴스는 [of] 로만 만든다.
 */
@Entity
@Table(name = "chat_messages")
class ChatMessage private constructor(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "chat_room_id", nullable = false)
    val chatRoom: ChatRoom,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "sender_id", nullable = false)
    val sender: Member,
    @field:Column(nullable = false, length = 1000)
    val content: String,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /** 연관 프록시의 식별자만 반환한다(식별자 접근은 프록시 초기화를 유발하지 않음). */
    val chatRoomId: Long? get() = chatRoom.id
    val senderId: Long? get() = sender.id

    companion object {
        @JvmStatic
        fun of(
            chatRoom: ChatRoom,
            sender: Member,
            content: String,
        ): ChatMessage = ChatMessage(chatRoom, sender, content)
    }
}
