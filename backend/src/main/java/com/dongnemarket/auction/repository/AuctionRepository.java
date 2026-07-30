package com.dongnemarket.auction.repository;

import com.dongnemarket.auction.entity.Auction;
import com.dongnemarket.auction.entity.AuctionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    /** 목록 조회: 최신 등록순. */
    List<Auction> findAllByOrderByCreatedAtDesc();

    /** 종료 스케줄러: 아직 진행 중인데 종료시각이 지난 경매. */
    List<Auction> findByStatusAndEndAtBefore(AuctionStatus status, LocalDateTime time);
}
