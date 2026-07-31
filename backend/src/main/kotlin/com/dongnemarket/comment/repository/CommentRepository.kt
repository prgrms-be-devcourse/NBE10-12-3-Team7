package com.dongnemarket.comment.repository

import com.dongnemarket.comment.entity.Comment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface CommentRepository : JpaRepository<Comment, Long> {
    /**
     * 상품의 삭제되지 않은 댓글을 작성순으로 조회한다. 작성자 닉네임 노출을 위해 작성자를 함께 로딩한다.
     *
     * `JOIN FETCH c.member` 로 작성자를 한 쿼리에 함께 로딩해 목록 매핑 시의 N+1을 제거한다.
     * 작성자는 `@ManyToOne` 단건 연관이라 행 증식이 없어 컬렉션 fetch join 의 메모리 페이징 문제(HHH000104)에 해당하지 않는다.
     */
    @Query(
        "SELECT c FROM Comment c JOIN FETCH c.member " +
            "WHERE c.product.id = :productId AND c.deletedAt IS NULL " +
            "ORDER BY c.createdAt ASC",
    )
    fun findAllWithMemberByProduct_Id(
        @Param("productId") productId: Long,
    ): List<Comment>

    fun findByIdAndDeletedAtIsNull(id: Long): Optional<Comment>
}
