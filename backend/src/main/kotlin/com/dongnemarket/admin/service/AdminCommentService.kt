package com.dongnemarket.admin.service

import com.dongnemarket.admin.dto.AdminCommentResponse
import com.dongnemarket.admin.repository.AdminCommentRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AdminCommentService(
    private val adminCommentRepository: AdminCommentRepository,
) {
    /** 전체 댓글 목록 (삭제 포함) */
    fun getComments(): List<AdminCommentResponse> = adminCommentRepository.findAll().map(AdminCommentResponse::from)

    /**
     * 댓글 소프트 삭제 (관리자는 작성자가 아니어도 삭제 가능).
     *
     * Optional 은 repository 시그니처를 그대로 유지한 결과다. 전환 마지막 단계에서
     * repository 와 함께 Comment? 로 바꾼다(지금 바꾸면 변경 범위가 넓어져 원인 추적이 어렵다).
     */
    @Transactional
    fun deleteComment(commentId: Long) {
        val comment =
            adminCommentRepository
                .findByIdAndDeletedAtIsNull(commentId)
                .orElseThrow { BusinessException(ErrorCode.COMMENT_NOT_FOUND) }
        comment.softDelete()
    }
}
