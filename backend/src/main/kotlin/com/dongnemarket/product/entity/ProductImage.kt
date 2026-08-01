package com.dongnemarket.product.entity

import com.dongnemarket.global.common.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * 상품 이미지. 정렬 순서와 대표 여부를 갖고, 생성 후 변경하지 않는다.
 *
 * 프로퍼티명이 `representative` 가 아니라 `isRepresentative` 인 이유: Kotlin 은 `is` 로 시작하는
 * Boolean 프로퍼티의 게터를 이름 그대로(`isRepresentative()`) 내보낸다. `representative` 로 두면
 * `getRepresentative()` 가 되어 아직 Java 인 호출부 5곳(`ProductImage::isRepresentative` 메서드
 * 참조 포함)이 깨진다.
 *
 * 그 대신 백킹 필드명이 바뀌므로 `@Column(name = "representative")` 로 컬럼명을 고정한다.
 * 없으면 `is_representative` 를 찾아 `ddl-auto: validate` 가 실패한다
 * (V1__baseline.sql 의 실제 컬럼명은 `representative`).
 */
@Entity
@Table(name = "product_images")
class ProductImage private constructor(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "product_id", nullable = false)
    val product: Product,
    @field:Column(nullable = false, length = 1000)
    val imageUrl: String,
    @field:Column(nullable = false)
    val sortOrder: Int,
    @field:Column(name = "representative", nullable = false)
    val isRepresentative: Boolean,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    companion object {
        @JvmStatic
        fun create(
            product: Product,
            imageUrl: String,
            sortOrder: Int,
            representative: Boolean,
        ): ProductImage = ProductImage(product, imageUrl, sortOrder, representative)
    }
}
