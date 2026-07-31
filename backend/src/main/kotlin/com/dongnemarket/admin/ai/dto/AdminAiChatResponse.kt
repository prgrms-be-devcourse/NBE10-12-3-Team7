package com.dongnemarket.admin.ai.dto

/**
 * AI 어시스턴트 답변 응답.
 * ```
 * { "answer": "현재 전체 회원은 12명이며..." }
 * ```
 * `answer` 가 nullable 인 것은 ChatClient 의 content() 가 null 을 돌려줄 수 있기 때문이다
 * (원본 Java 도 null 을 그대로 담을 수 있었다).
 */
@ConsistentCopyVisibility
data class AdminAiChatResponse private constructor(
    val answer: String?,
) {
    companion object {
        @JvmStatic
        fun of(answer: String?): AdminAiChatResponse = AdminAiChatResponse(answer)
    }
}
