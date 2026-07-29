package com.dongnemarket.auction.entity;

import com.dongnemarket.global.common.BaseTimeEntity;
import com.dongnemarket.global.exception.BusinessException;
import com.dongnemarket.global.exception.ErrorCode;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "auctions")
public class Auction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private BigDecimal currentPrice;      // 시작가로 초기화, 입찰마다 최고가로 갱신

    @Column(name = "highest_bidder_id")
    private Long highestBidderId;         // 최고가 입찰자 memberId (아직 없으면 null)

    @Column(nullable = false)
    private LocalDateTime endAt;          // 고정 종료 시각

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuctionStatus status;

    @Version
    private Long version;                 // 낙관적 락 — 동시 입찰 경합 해결

    protected Auction() {
    }

    private Auction(String title, String imageUrl, String description, BigDecimal startPrice, LocalDateTime endAt) {
        this.title = title;
        this.imageUrl = imageUrl;
        this.description = description;
        this.currentPrice = startPrice;
        this.endAt = endAt;
        this.status = AuctionStatus.ONGOING;
    }

    /** 경매 등록: 시작가·종료시각으로 진행 중(ONGOING) 상태 생성. */
    public static Auction create(String title, String imageUrl, String description,
                                 BigDecimal startPrice, LocalDateTime endAt) {
        return new Auction(title, imageUrl, description, startPrice, endAt);
    }

    /** 입찰: 진행 중이고 현재가보다 높을 때만 최고가·최고입찰자를 갱신한다. */
    public void placeBid(Long bidderId, BigDecimal amount) {
        if (this.status != AuctionStatus.ONGOING || LocalDateTime.now().isAfter(this.endAt)) {
            throw new BusinessException(ErrorCode.AUCTION_NOT_ONGOING);
        }
        if (amount.compareTo(this.currentPrice) <= 0) {
            throw new BusinessException(ErrorCode.BID_TOO_LOW);
        }
        this.currentPrice = amount;
        this.highestBidderId = bidderId;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getImageUrl() { return imageUrl; }
    public String getDescription() { return description; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public Long getHighestBidderId() { return highestBidderId; }
    public LocalDateTime getEndAt() { return endAt; }
    public AuctionStatus getStatus() { return status; }
    public Long getVersion() { return version; }
}