package com.dongnemarket.region.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import java.math.BigDecimal

/**
 * 행정구역 마스터. 시·도(level 1) → 시·군·구(2) → 읍·면·동(3) 계층을 `parent` 로 잇는다.
 *
 * `@BatchSize` 가 있는 이유: `Product.regionRef` 가 `LAZY` 라 상품 목록을 만들 때
 * [com.dongnemarket.product.dto.ProductSummaryResponse] 가 `regionCode`·`regionName`·
 * `regionFullName` 을 읽는 순간 프록시가 깨진다. 지역이 상품마다 다르면 30건짜리 목록에
 * 지역 조회가 최대 30번 따라붙는다(N+1). 이 어노테이션이 붙으면 Hibernate 가 대기 중인
 * 프록시를 모아 `where id in (...)` 한 번으로 가져온다 — 목록 1건 기준 32쿼리 → 3쿼리.
 *
 * 전역 설정(`hibernate.default_batch_fetch_size`)으로도 같은 효과를 낼 수 있지만
 * `application.yml` 은 팀장 영역이고 전 도메인에 영향이 간다. Region 으로 한정한다.
 *
 * 100 은 실측값이 아니라 상품 목록 최대 페이지 크기(`ProductService.MAX_PAGE_SIZE`)에 맞춘 값이다.
 */
@Entity
@BatchSize(size = 100)
@Table(
    name = "regions",
    indexes = [
        Index(name = "idx_regions_code", columnList = "code"),
        Index(name = "idx_regions_parent_id", columnList = "parent_id"),
        Index(name = "idx_regions_level", columnList = "level"),
    ],
)
class Region private constructor(
    @field:Column(unique = true, length = 10)
    val code: String,
    @field:Column(nullable = false)
    val level: Int,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "parent_id")
    val parent: Region?,
    @field:Column(nullable = false, length = 100)
    val fullName: String,
    @field:Column(nullable = false, length = 50)
    val displayName: String,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Column(precision = 10, scale = 7)
    val latitude: BigDecimal? = null

    @field:Column(precision = 10, scale = 7)
    val longitude: BigDecimal? = null

    companion object {
        /** 최상위 지역(시·도). 부모가 없고 level 은 1 로 고정한다. */
        @JvmStatic
        fun root(
            code: String,
            fullName: String,
            displayName: String,
        ): Region = Region(code, 1, null, fullName, displayName)

        /** 하위 지역(시·군·구, 읍·면·동). 부모와 level 을 받아 계층을 잇는다. */
        @JvmStatic
        fun child(
            code: String,
            level: Int,
            parent: Region?,
            fullName: String,
            displayName: String,
        ): Region = Region(code, level, parent, fullName, displayName)
    }
}
