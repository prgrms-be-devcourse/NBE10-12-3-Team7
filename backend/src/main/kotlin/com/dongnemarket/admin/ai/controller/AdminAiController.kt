package com.dongnemarket.admin.ai.controller

import com.dongnemarket.admin.ai.dto.AdminAiChatRequest
import com.dongnemarket.admin.ai.dto.AdminAiChatResponse
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.ai.chat.client.ChatClient
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin - AI", description = "관리자 AI 어시스턴트 API (읽기 전용)")
@RestController
@RequestMapping("/api/admin/ai")
class AdminAiController(
    private val adminChatClient: ChatClient,
) {
    @Operation(
        summary = "AI 어시스턴트 질문",
        description = "관리자가 자연어로 질문하면 LLM이 조회 tool을 호출해 답변한다.",
    )
    @PostMapping("/chat")
    fun chat(
        @RequestBody request: AdminAiChatRequest?,
    ): ResponseEntity<ApiResponse<AdminAiChatResponse>> {
        val message = validateMessage(request)
        val answer =
            adminChatClient
                .prompt()
                .user(message)
                .call()
                .content()
        return ResponseEntity.ok(ApiResponse.success(AdminAiChatResponse.of(answer)))
    }

    /** 원본의 `request == null || getMessage() == null || getMessage().isBlank()` 3조건이 2줄로. */
    private fun validateMessage(request: AdminAiChatRequest?): String {
        val message = request?.message
        if (message.isNullOrBlank()) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }
        return message.trim()
    }
}
