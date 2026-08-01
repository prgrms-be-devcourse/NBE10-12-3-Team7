package com.dongnemarket.manner.entity

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
import jakarta.persistence.UniqueConstraint

/**
 * 거래 완료 후 구매자가 판매자에게 남기는 별점(1~5). 매너온도의 입력값 중 하나다.
 *
 * 한 거래(상품+구매자)당 별점은 한 번만 남길 수 있다 — `UNIQUE(product_id, rater_id)`로 보장한다.
 * 등록 가능 여부(그 상품 채팅방 참여자인지, 거래가 실제로 완료됐는지)는 서비스 레이어에서 검증하고,
 * 이 엔티티는 "이미 검증된 별점 하나"만 표현한다.
 */
@Entity
@Table(
    name = "manner_ratings",
    uniqueConstraints = [UniqueConstraint(name = "uk_manner_ratings_product_rater", columnNames = ["product_id", "rater_id"])],
)
class MannerRating private constructor(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "product_id", nullable = false)
    val product: Product,
    /** 별점을 남긴 구매자 */
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "rater_id", nullable = false)
    val rater: Member,
    /** 별점을 받는 판매자 */
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "ratee_id", nullable = false)
    val ratee: Member,
    @field:Column(nullable = false)
    val score: Int,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    companion object {
        @JvmStatic
        fun of(
            product: Product,
            rater: Member,
            ratee: Member,
            score: Int,
        ): MannerRating = MannerRating(product, rater, ratee, score)
    }
}
