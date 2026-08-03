package com.dongnemarket.member.entity

import com.dongnemarket.global.common.BaseTimeEntity
import com.dongnemarket.region.entity.Region
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
 * 회원이 설정한 동네(최대 2개). 전체 교체 방식으로만 갱신된다(MemberLocationService).
 *
 * [isActive] 의 `@Column(name = "active")` 가 필수인 이유: Java 호출부가 쓰는 `isActive()` 게터를
 * 유지하려면 프로퍼티명이 `isActive` 여야 하는데, 그러면 컬럼이 `is_active` 가 된다.
 * 실제 컬럼은 `active`(V1__baseline.sql) 이므로 이름을 명시해 고정한다 — Product.isHidden 과 같은 규칙.
 */
@Entity
@Table(
    name = "member_locations",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_member_locations_member_region_id",
            columnNames = ["member_id", "region_id"],
        ),
    ],
)
class MemberLocation private constructor(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "member_id", nullable = false)
    val member: Member,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "region_id", nullable = false)
    val regionRef: Region,
    @field:Column(nullable = false)
    val sortOrder: Int,
    @field:Column(name = "active", nullable = false)
    val isActive: Boolean,
) : BaseTimeEntity() {

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /** 지역 정보는 Region 에서 파생한다(비정규화 컬럼 아님) — Product 의 파생 프로퍼티와 같은 규칙. */
    val regionCode: String get() = regionRef.code

    val regionName: String get() = regionRef.displayName

    val regionFullName: String get() = regionRef.fullName

    companion object {
        @JvmStatic
        fun create(
            member: Member,
            regionRef: Region,
            sortOrder: Int,
            active: Boolean,
        ): MemberLocation = MemberLocation(member, regionRef, sortOrder, active)
    }
}
