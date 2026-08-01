package com.dongnemarket.manner.repository

import com.dongnemarket.manner.entity.MannerRating
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface MannerRatingRepository : JpaRepository<MannerRating, Long> {
    fun existsByProduct_IdAndRater_Id(
        productId: Long,
        raterId: Long,
    ): Boolean

    /** 최근 30일간 판매자가 받은 별점 건수 — 스케줄링 회복 로직의 "정상 거래 완료 건수" 근사치로 함께 활용. */
    @Query("SELECT COUNT(r) FROM MannerRating r WHERE r.ratee.id = :sellerId AND r.createdAt >= :since")
    fun countByRateeSince(
        @Param("sellerId") sellerId: Long,
        @Param("since") since: LocalDateTime,
    ): Long
}
