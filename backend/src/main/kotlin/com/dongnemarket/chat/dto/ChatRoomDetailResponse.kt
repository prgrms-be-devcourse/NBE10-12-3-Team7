package com.dongnemarket.chat.dto

import com.dongnemarket.chat.entity.ChatRoom

/** 채팅방 입장 응답. 상품 상세 + 판매자 정보. 방 생성(get-or-create) 및 입장 시 반환한다. */
@ConsistentCopyVisibility
data class ChatRoomDetailResponse private constructor(
    val roomId: Long?,
    val product: ChatProductDetail,
    val seller: ChatMemberSummary,
) {
    companion object {
        @JvmStatic
        fun of(room: ChatRoom): ChatRoomDetailResponse =
            ChatRoomDetailResponse(
                room.id,
                ChatProductDetail.from(room.product),
                ChatMemberSummary.of(room.seller),
            )
    }
}
