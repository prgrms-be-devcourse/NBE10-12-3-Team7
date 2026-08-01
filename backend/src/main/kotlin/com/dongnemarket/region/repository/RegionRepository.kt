package com.dongnemarket.region.repository

import com.dongnemarket.region.entity.Region
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.Optional

interface RegionRepository : JpaRepository<Region, Long> {
    fun existsByCode(code: String): Boolean

    /** 반환형을 Optional 로 유지한다 — 아직 Java 인 호출부가 `.orElseThrow()` 를 쓴다. */
    fun findByCode(code: String): Optional<Region>

    fun findAllByParentIsNullOrderByDisplayNameAsc(): List<Region>

    fun findAllByParentCodeOrderByDisplayNameAsc(parentCode: String): List<Region>

    fun findAllByCodeIn(codes: List<String>): List<Region>

    fun countByLevel(level: Int): Long

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Region r where r.level = :level")
    fun deleteByLevel(level: Int)
}
