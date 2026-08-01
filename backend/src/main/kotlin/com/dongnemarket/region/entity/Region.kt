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
import java.math.BigDecimal

@Entity
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
