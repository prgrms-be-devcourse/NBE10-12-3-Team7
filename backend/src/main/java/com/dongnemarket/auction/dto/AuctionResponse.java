package com.dongnemarket.auction.dto;

import com.dongnemarket.auction.entity.Auction;
import com.dongnemarket.auction.entity.AuctionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AuctionResponse {

    private final Long auctionId;
    private final Long sellerId;
    private final String title;
    private final String imageUrl;
    private final String description;
    private final BigDecimal currentPrice;
    private final Long highestBidderId;
    private final AuctionStatus status;
    private final LocalDateTime endAt;
    private final LocalDateTime createdAt;

    private AuctionResponse(Long auctionId, Long sellerId, String title, String imageUrl, String description,
                            BigDecimal currentPrice, Long highestBidderId, AuctionStatus status,
                            LocalDateTime endAt, LocalDateTime createdAt) {
        this.auctionId = auctionId;
        this.sellerId = sellerId;
        this.title = title;
        this.imageUrl = imageUrl;
        this.description = description;
        this.currentPrice = currentPrice;
        this.highestBidderId = highestBidderId;
        this.status = status;
        this.endAt = endAt;
        this.createdAt = createdAt;
    }

    public static AuctionResponse from(Auction a) {
        return new AuctionResponse(
                a.getId(), a.getSellerId(), a.getTitle(), a.getImageUrl(), a.getDescription(),
                a.getCurrentPrice(), a.getHighestBidderId(), a.getStatus(),
                a.getEndAt(), a.getCreatedAt());
    }

    public Long getAuctionId() { return auctionId; }
    public Long getSellerId() { return sellerId; }
    public String getTitle() { return title; }
    public String getImageUrl() { return imageUrl; }
    public String getDescription() { return description; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public Long getHighestBidderId() { return highestBidderId; }
    public AuctionStatus getStatus() { return status; }
    public LocalDateTime getEndAt() { return endAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
