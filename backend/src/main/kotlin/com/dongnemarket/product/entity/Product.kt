package com.dongnemarket.product.entity

import com.dongnemarket.category.entity.Category
import com.dongnemarket.global.common.BaseTimeEntity
import com.dongnemarket.member.entity.Member
import com.dongnemarket.region.entity.Region
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 판매 상품. 등록 후 카테고리·제목·설명·가격·지역이 수정될 수 있고, 숨김·삭제·거래상태가 바뀐다.
 *
 * `data class` 가 아니라 `class` — equals/hashCode 가 지연 로딩·JPA 동일성과 어긋난다.
 * 상태를 바꾸는 프로퍼티는 `var` + `protected set` 이라 외부에서는 아래 메서드로만 변경한다.
 *
 * `isHidden` 의 `@Column(name = "hidden")` 이 필수인 이유: Kotlin 은 프로퍼티 이름 하나로
 * 필드명과 게터명을 함께 결정한다. Java 호출부 8곳이 쓰는 `isHidden()` 게터를 유지하려면
 * 프로퍼티명이 `isHidden` 이어야 하는데, 그러면 컬럼이 `is_hidden` 이 된다.
 * 실제 컬럼은 `hidden`(V1__baseline.sql) 이므로 이름을 명시해 고정한다.
 * 빠뜨리면 테스트(create-drop)는 전부 통과하고 운영(validate)에서만 기동이 실패한다.
 */
@Entity
@Table(name = "products")
class Product private constructor(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "member_id", nullable = false)
    val member: Member,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "category_id", nullable = false)
    var category: Category,
    @field:Column(nullable = false, length = 100)
    var title: String,
    @field:Column(nullable = false, length = 1000)
    var description: String,
    @field:Column(nullable = false)
    var price: BigDecimal,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "region_id", nullable = false)
    var regionRef: Region,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    var tradeStatus: TradeStatus = TradeStatus.ON_SALE
        protected set

    @field:Column(nullable = false)
    var viewCount: Long = 0L
        protected set

    /** 찜 수. 애플리케이션이 아니라 ProductRepository 의 벌크 update 가 증감시킨다. */
    @field:Column(nullable = false)
    var favoriteCount: Int = 0
        protected set

    @field:Column(length = 1000)
    var thumbnailUrl: String? = null
        protected set

    @field:Column(name = "hidden", nullable = false)
    var isHidden: Boolean = false
        protected set

    @field:Column
    var deletedAt: LocalDateTime? = null
        protected set

    @field:Column
    var completedAt: LocalDateTime? = null
        protected set

    val isDeleted: Boolean get() = deletedAt != null

    val isCompleted: Boolean get() = tradeStatus == TradeStatus.COMPLETED

    /** 지역 정보는 Region 에서 파생한다(비정규화 컬럼 아님). */
    val regionCode: String get() = regionRef.code

    val regionName: String get() = regionRef.displayName

    val regionFullName: String get() = regionRef.fullName

    fun hide() {
        isHidden = true
    }

    fun softDelete() {
        deletedAt = LocalDateTime.now()
    }

    fun increaseViewCount() {
        viewCount++
    }

    fun update(
        category: Category,
        title: String,
        description: String,
        price: BigDecimal,
        regionRef: Region,
    ) {
        this.category = category
        this.title = title
        this.description = description
        this.price = price
        this.regionRef = regionRef
    }

    fun changeTradeStatus(tradeStatus: TradeStatus) {
        this.tradeStatus = tradeStatus
        if (tradeStatus == TradeStatus.COMPLETED && completedAt == null) {
            completedAt = LocalDateTime.now()
        }
    }

    fun changeThumbnailUrl(thumbnailUrl: String?) {
        this.thumbnailUrl = thumbnailUrl
    }

    fun complete() {
        changeTradeStatus(TradeStatus.COMPLETED)
    }

    companion object {
        @JvmStatic
        fun create(
            member: Member,
            category: Category,
            title: String,
            description: String,
            price: BigDecimal,
            regionRef: Region,
        ): Product = Product(member, category, title, description, price, regionRef)
    }
}
