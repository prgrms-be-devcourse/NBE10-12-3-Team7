package com.dongnemarket.comment.entity

import com.dongnemarket.global.common.BaseTimeEntity
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 상품에 달린 댓글 한 건. 작성자 본인만 수정·삭제할 수 있고, 삭제는 [softDelete] 로 처리해
 * 목록에서만 감추고 행은 보존한다(deleted_at 스탬프).
 *
 * `data class` 가 아니라 `class` — equals/hashCode 가 지연 로딩·JPA 동일성과 어긋난다.
 * JPA 어노테이션은 `@field:` 로 백킹 필드에 붙이고(필드 접근), 인스턴스는 [of] 로만 만든다
 * (Java 호출부가 남아 있어 `@JvmStatic`). 수정 가능한 `content` 는 주 생성자 프로퍼티가 아니라
 * 본문 `var` 로 둔다(주 생성자 프로퍼티에는 `protected set` 을 붙일 수 없다).
 *
 * member·product 가 `Member?`/`Product?` 인 이유: admin 삭제 단위 테스트가
 * `Comment.of(null, null, ...)` 로 연관 없이 엔티티를 만든다(softDelete 만 검증하므로 연관이 불필요).
 * DB 컬럼은 `nullable = false` 라 실제 저장 경로에서는 항상 채워지며, 이 nullability 는 그 테스트
 * 편의를 흡수하기 위한 것이다. AdminCommentResponse 가 이미 memberId/productId 를 `Long?` 로
 * 받으므로 이 선택으로 comment 도메인 밖 파일은 건드리지 않는다.
 */
@Entity
@Table(name = "comments")
class Comment private constructor(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "member_id", nullable = false)
    val member: Member?,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "product_id", nullable = false)
    val product: Product?,
    content: String,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Column(nullable = false, length = 500)
    var content: String = content
        protected set

    @field:Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
        protected set

    /** 연관 프록시의 식별자만 반환한다(식별자 접근은 프록시 초기화를 유발하지 않음). */
    val memberId: Long? get() = member?.id
    val productId: Long? get() = product?.id

    /** 소프트 삭제 여부. deleted_at 스탬프 유무로 판단한다. */
    val isDeleted: Boolean get() = deletedAt != null

    fun updateContent(content: String) {
        this.content = content
    }

    fun softDelete() {
        deletedAt = LocalDateTime.now()
    }

    companion object {
        @JvmStatic
        fun of(
            member: Member?,
            product: Product?,
            content: String,
        ): Comment = Comment(member, product, content)
    }
}
