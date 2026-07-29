package com.dongnemarket.auction.dto;

import com.dongnemarket.auction.entity.Auction;
import com.dongnemarket.auction.entity.AuctionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AuctionStateResponse {

    private final Long auctionId;
    private final BigDecimal currentPrice;
    private final Long highestBidderId;
    private final AuctionStatus status;
    private final LocalDateTime endAt;

    private AuctionStateResponse(Long auctionId, BigDecimal currentPrice, Long highestBidderId,
                                 AuctionStatus status, LocalDateTime endAt) {
        this.auctionId = auctionId;
        this.currentPrice = currentPrice;
        this.highestBidderId = highestBidderId;
        this.status = status;
        this.endAt = endAt;
    }

    public static AuctionStateResponse from(Auction auction) {
        return new AuctionStateResponse(
                auction.getId(), auction.getCurrentPrice(), auction.getHighestBidderId(),
                auction.getStatus(), auction.getEndAt());
    }

    public Long getAuctionId() { return auctionId; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public Long getHighestBidderId() { return highestBidderId; }
    public AuctionStatus getStatus() { return status; }
    public LocalDateTime getEndAt() { return endAt; }
}
