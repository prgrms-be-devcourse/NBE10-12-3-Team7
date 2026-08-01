package com.dongnemarket.chat.dto

import jakarta.validation.constraints.NotNull

/** 채팅방 생성(get-or-create) 요청. */
data class ChatRoomCreateRequest(
    @field:NotNull
    val productId: Long?,
)
