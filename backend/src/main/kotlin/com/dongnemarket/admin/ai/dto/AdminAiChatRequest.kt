package com.dongnemarket.admin.ai.dto

/**
 * AI 어시스턴트 질문 요청.
 * ```
 * { "message": "대시보드 현황 알려줘" }
 * ```
 * `message` 가 nullable 인 것은 원본 Java 를 그대로 옮긴 결과다 —
 * AdminAiController 가 null·공백을 INVALID_INPUT_VALUE 로 방어한다.
 */
data class AdminAiChatRequest(
    val message: String?,
)
