package com.dongnemarket.auction.repository

import com.dongnemarket.auction.entity.Auction
import com.dongnemarket.auction.entity.AuctionStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface AuctionRepository : JpaRepository<Auction, Long> {
    /** 목록 조회: 최신 등록순. */
    fun findAllByOrderByCreatedAtDesc(): List<Auction>

    /** 종료 스케줄러: 아직 진행 중인데 종료시각이 지난 경매. */
    fun findByStatusAndEndAtBefore(
        status: AuctionStatus,
        time: LocalDateTime,
    ): List<Auction>
}
