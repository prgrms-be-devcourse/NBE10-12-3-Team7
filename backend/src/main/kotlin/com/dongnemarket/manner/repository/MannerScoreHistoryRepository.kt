package com.dongnemarket.manner.repository

import com.dongnemarket.manner.entity.MannerScoreChangeReason
import com.dongnemarket.manner.entity.MannerScoreHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface MannerScoreHistoryRepository : JpaRepository<MannerScoreHistory, Long> {
    fun findAllByMember_IdOrderByCreatedAtDesc(memberId: Long): List<MannerScoreHistory>

    /** 최근 90일 내 "정당 신고 확정으로 인한 하락"이 몇 건인지 — 계정 자동 정지 판단에 사용. */
    @Query(
        "SELECT COUNT(h) FROM MannerScoreHistory h " +
            "WHERE h.member.id = :memberId AND h.reason = :reason AND h.createdAt >= :since",
    )
    fun countByMemberAndReasonSince(
        @Param("memberId") memberId: Long,
        @Param("reason") reason: MannerScoreChangeReason,
        @Param("since") since: LocalDateTime,
    ): Long

    /** 최근 30일 내 감점 이력(신고 확정/무고성 페널티)이 있는지 — 있으면 회복 배치 대상에서 제외. */
    @Query(
        "SELECT COUNT(h) > 0 FROM MannerScoreHistory h " +
            "WHERE h.member.id = :memberId " +
            "AND h.reason IN (com.dongnemarket.manner.entity.MannerScoreChangeReason.REPORT_CONFIRMED, " +
            "                 com.dongnemarket.manner.entity.MannerScoreChangeReason.FALSE_REPORT_PENALTY) " +
            "AND h.createdAt >= :since",
    )
    fun hasPenaltySince(
        @Param("memberId") memberId: Long,
        @Param("since") since: LocalDateTime,
    ): Boolean
}
