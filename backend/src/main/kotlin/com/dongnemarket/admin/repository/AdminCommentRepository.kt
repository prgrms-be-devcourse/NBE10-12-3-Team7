package com.dongnemarket.admin.repository

import com.dongnemarket.comment.entity.Comment
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Admin 전용 댓글 Repository.
 * 팀원(comment) Repository를 수정하지 않기 위해 admin 패키지에 별도로 둔다.
 * 목록은 삭제 여부와 무관하게 전체를, 삭제 대상은 아직 삭제되지 않은 댓글만 조회한다.
 *
 * 반환 타입이 `Optional<Comment>` 가 아니라 `Comment?` 인 것은 Spring Data 가 Kotlin 의
 * nullability 를 그대로 읽기 때문이다 — `Comment?` 면 "없으면 null", `Comment` 면
 * "없으면 EmptyResultDataAccessException". Optional 포장이 필요 없다.
 */
interface AdminCommentRepository : JpaRepository<Comment, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): Comment?
}
