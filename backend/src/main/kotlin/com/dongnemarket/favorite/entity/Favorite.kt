package com.dongnemarket.favorite.entity

import com.dongnemarket.global.common.BaseTimeEntity
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 회원이 특정 상품을 관심 등록한 기록. `UNIQUE(member_id, product_id)` 로 중복 등록을 막는다.
 *
 * `data class` 가 아니라 `class` — equals/hashCode 가 지연 로딩·JPA 동일성과 어긋난다.
 * JPA 어노테이션은 `@field:` 로 백킹 필드에 붙이고(필드 접근), 연관은 생성 후 불변이라 `val` 로 둔다.
 * 인스턴스는 [of] 로만 만든다(Java 에서도 호출하므로 `@JvmStatic`).
 */
@Entity
@Table(
    name = "favorites",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_favorites_member_product",
            columnNames = ["member_id", "product_id"],
        ),
    ],
)
class Favorite private constructor(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "member_id", nullable = false)
    val member: Member,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "product_id", nullable = false)
    val product: Product,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /** 연관 프록시의 식별자만 반환한다(식별자 접근은 프록시 초기화를 유발하지 않음). */
    val memberId: Long get() = member.id
    val productId: Long get() = product.id

    companion object {
        @JvmStatic
        fun of(
            member: Member,
            product: Product,
        ): Favorite = Favorite(member, product)
    }
}
