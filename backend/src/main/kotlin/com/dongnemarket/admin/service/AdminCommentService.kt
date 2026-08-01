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
     * `Optional.orElseThrow { }` 대신 엘비스(`?:`)를 쓴다. admin 패키지가 전부 Kotlin 이 된
     * 뒤에야 repository 반환 타입을 `Comment?` 로 바꿀 수 있었다 — Java 호출부가 남아 있으면
     * `Optional` 이 사라지는 순간 그쪽이 깨진다.
     */
    @Transactional
    fun deleteComment(commentId: Long) {
        val comment =
            adminCommentRepository.findByIdAndDeletedAtIsNull(commentId)
                ?: throw BusinessException(ErrorCode.COMMENT_NOT_FOUND)
        comment.softDelete()
    }
}
