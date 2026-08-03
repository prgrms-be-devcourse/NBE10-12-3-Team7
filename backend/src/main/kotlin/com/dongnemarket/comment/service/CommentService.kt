package com.dongnemarket.comment.service

import com.dongnemarket.comment.dto.CommentCreateRequest
import com.dongnemarket.comment.dto.CommentResponse
import com.dongnemarket.comment.dto.CommentUpdateRequest
import com.dongnemarket.comment.entity.Comment
import com.dongnemarket.comment.repository.CommentRepository
import com.dongnemarket.global.common.event.CommentCreatedEvent
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.service.ProductService
import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CommentService(
    private val commentRepository: CommentRepository,
    private val productService: ProductService,
    private val entityManager: EntityManager,
    private val eventPublisher: ApplicationEventPublisher,
) {
    /** 댓글 작성. 로그인 사용자가 접근 가능한 상품에 댓글을 단다. */
    @Transactional
    fun create(
        memberId: Long,
        productId: Long,
        request: CommentCreateRequest,
    ): CommentResponse {
        productService.validateAccessibleProduct(productId)

        val member = entityManager.find(Member::class.java, memberId)
        val product = entityManager.find(Product::class.java, productId)
        val saved = commentRepository.save(Comment.of(member, product, request.content))

        // 상품 소유자에게 댓글 알림(자기 상품에 스스로 단 댓글은 제외). 프록시 id 접근이라 추가 쿼리 없음.
        // 커밋 후(AFTER_COMMIT) 별도 트랜잭션에서 저장되므로 알림 실패가 댓글 작성을 롤백하지 않는다.
        val recipientId = product.member.id!!
        if (recipientId != memberId) {
            eventPublisher.publishEvent(CommentCreatedEvent(recipientId, productId, product.title, memberId))
        }
        return CommentResponse.from(saved)
    }

    /** 댓글 목록 조회. 접근 가능한 상품의 삭제되지 않은 댓글을 조회한다. 비로그인도 가능하다. */
    fun getComments(productId: Long): List<CommentResponse> {
        productService.validateAccessibleProduct(productId)
        return commentRepository
            .findAllWithMemberByProduct_Id(productId)
            .map { CommentResponse.from(it) }
    }

    /** 댓글 수정. 작성자 본인만 자신의 댓글 내용을 수정할 수 있다. */
    @Transactional
    fun update(
        memberId: Long,
        commentId: Long,
        request: CommentUpdateRequest,
    ): CommentResponse {
        val comment =
            commentRepository
                .findByIdAndDeletedAtIsNull(commentId)
                .orElseThrow { BusinessException(ErrorCode.COMMENT_NOT_FOUND) }
        if (comment.memberId != memberId) {
            throw BusinessException(ErrorCode.COMMENT_OWNER_ONLY)
        }
        comment.updateContent(request.content)
        return CommentResponse.from(comment)
    }

    /** 댓글 삭제. 작성자 본인만 자신의 댓글을 소프트 삭제한다. */
    @Transactional
    fun delete(
        memberId: Long,
        commentId: Long,
    ) {
        val comment =
            commentRepository
                .findByIdAndDeletedAtIsNull(commentId)
                .orElseThrow { BusinessException(ErrorCode.COMMENT_NOT_FOUND) }
        if (comment.memberId != memberId) {
            throw BusinessException(ErrorCode.COMMENT_OWNER_ONLY)
        }
        comment.softDelete()
    }
}
