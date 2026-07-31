package com.dongnemarket.chat.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** 메시지 전송 요청. */
data class ChatMessageCreateRequest(
    @field:NotBlank
    @field:Size(max = 1000)
    val content: String,
)
