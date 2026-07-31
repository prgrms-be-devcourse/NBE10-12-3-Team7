package com.dongnemarket.admin.service

import com.dongnemarket.admin.repository.AdminCommentRepository
import com.dongnemarket.comment.entity.Comment
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

/**
 * [단위] AdminCommentService.deleteComment — 서비스 고유 로직만 검증.
 *  - 검증 대상: 성공(찾아서 softDelete) + NOT_FOUND 예외.
 *  - 제외: getComments 위임(통합), "이미 삭제=404" 판별은 findByIdAndDeletedAtIsNull 쿼리(리포지토리/통합).
 */
@ExtendWith(MockitoExtension::class)
class AdminCommentServiceTest {
    @Mock
    lateinit var adminCommentRepository: AdminCommentRepository

    @InjectMocks
    lateinit var adminCommentService: AdminCommentService

    @Nested
    @DisplayName("성공 케이스")
    inner class Success {
        @Test
        fun `미삭제 댓글을 삭제하면 softDelete 되어 deletedAt이 기록된다(작성자 불문)`() {
            val comment = Comment.of(null, null, "부적절한 댓글")
            given(adminCommentRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(comment)

            adminCommentService.deleteComment(1L)

            assertThat(comment.isDeleted).isTrue()
            assertThat(comment.deletedAt).isNotNull()
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    inner class Failure {
        @Test
        fun `없는(또는 이미 삭제된) 댓글을 삭제하면 COMMENT_NOT_FOUND 예외가 발생한다`() {
            given(adminCommentRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(null)

            val ex = assertThrows<BusinessException> { adminCommentService.deleteComment(999L) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.COMMENT_NOT_FOUND)
        }
    }
}
