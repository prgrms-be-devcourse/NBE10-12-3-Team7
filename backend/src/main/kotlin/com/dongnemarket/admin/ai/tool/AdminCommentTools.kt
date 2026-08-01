package com.dongnemarket.admin.ai.tool

import com.dongnemarket.admin.dto.AdminCommentResponse
import com.dongnemarket.admin.service.AdminCommentService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

/**
 * 댓글 조회 Tool (읽기 전용). 기존 AdminCommentService에 위임만 한다.
 */
@Component
class AdminCommentTools(
    private val adminCommentService: AdminCommentService,
) {
    @Tool(
        description =
            "전체 댓글 목록을 조회한다. 삭제된 댓글도 포함해 반환된다. " +
                "삭제 여부 등 특정 조건이 필요하면 이 목록에서 걸러서 답하라.",
    )
    fun listComments(): List<AdminCommentResponse> = adminCommentService.getComments()
}
