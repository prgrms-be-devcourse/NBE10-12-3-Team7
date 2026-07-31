package com.dongnemarket.chat.dto

import com.dongnemarket.chat.entity.ChatMessage
import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.member.entity.Member
import java.time.LocalDateTime

/** 내 채팅방 목록의 한 행. 상품 요약 + 상대방 + 방 생성일 + 마지막 메시지(없으면 null) + 안읽음 수. */
@ConsistentCopyVisibility
data class ChatRoomListResponse private constructor(
    val roomId: Long,
    val product: ChatProductSummary,
    val opponent: ChatMemberSummary,
    /** 요청자(나) 기준 역할. "BUYER" 또는 "SELLER" — 매너온도 후기 등록 등 구매자 전용 UI 노출 여부 판단용. */
    val viewerRole: String,
    val createdAt: LocalDateTime,
    val lastMessage: ChatMessageResponse?,
    val unreadCount: Long,
) {
    companion object {
        /**
         * opponent는 요청자 기준 상대방(구매자면 판매자, 판매자면 구매자), lastMessage는 없으면 null.
         * viewerRole은 요청자 본인의 역할("BUYER"/"SELLER"). unreadCount는 요청자가 아직 읽지 않은 상대 메시지 수(목록 배지용).
         *
         * roomId·createdAt 은 영속 방에서 나오므로 항상 존재한다(`!!`).
         */
        @JvmStatic
        fun of(
            room: ChatRoom,
            opponent: Member,
            viewerRole: String,
            lastMessage: ChatMessage?,
            unreadCount: Long,
        ): ChatRoomListResponse =
            ChatRoomListResponse(
                room.id!!,
                ChatProductSummary.from(room.product),
                ChatMemberSummary.of(opponent),
                viewerRole,
                room.createdAt!!,
                lastMessage?.let { ChatMessageResponse.from(it) },
                unreadCount,
            )
    }
}
