package com.dongnemarket.admin.dto

import com.dongnemarket.comment.entity.Comment
import java.time.LocalDateTime

/**
 * 관리자용 댓글 응답. 관리 목적상 deletedAt 까지 포함한다.
 */
@ConsistentCopyVisibility
data class AdminCommentResponse private constructor(
    val commentId: Long?,
    val memberId: Long?,
    val productId: Long?,
    val content: String,
    val deletedAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(comment: Comment): AdminCommentResponse =
            AdminCommentResponse(
                comment.id,
                comment.memberId,
                comment.productId,
                comment.content,
                comment.deletedAt,
                comment.createdAt,
            )
    }
}
