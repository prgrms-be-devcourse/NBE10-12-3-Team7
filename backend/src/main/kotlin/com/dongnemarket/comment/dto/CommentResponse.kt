package com.dongnemarket.comment.dto

import com.dongnemarket.comment.entity.Comment
import java.time.LocalDateTime

/** 댓글 응답. 작성자 닉네임은 탈퇴 시 "탈퇴한 사용자"로 마스킹된다(내용은 그대로 보존). */
@ConsistentCopyVisibility
data class CommentResponse private constructor(
    val id: Long?,
    val memberId: Long?,
    val authorNickname: String?,
    val productId: Long?,
    val content: String,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(comment: Comment): CommentResponse =
            CommentResponse(
                comment.id,
                comment.memberId,
                // 탈퇴한 작성자는 "탈퇴한 사용자"로 마스킹된다(Member.getDisplayNickname, 내용은 그대로 보존).
                comment.member?.displayNickname,
                comment.productId,
                comment.content,
                comment.createdAt,
                comment.updatedAt,
            )
    }
}
