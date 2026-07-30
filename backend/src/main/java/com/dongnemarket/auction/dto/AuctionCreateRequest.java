package com.dongnemarket.auction.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AuctionCreateRequest {

    @NotBlank(message = "제목은 필수입니다.")
    private String title;

    private String imageUrl;      // 선택
    private String description;   // 선택

    @NotNull(message = "시작가는 필수입니다.")
    @Positive(message = "시작가는 0보다 커야 합니다.")
    private BigDecimal startPrice;

    @NotNull(message = "종료 시각은 필수입니다.")
    @Future(message = "종료 시각은 미래여야 합니다.")
    private LocalDateTime endAt;

    protected AuctionCreateRequest() {
    }

    public AuctionCreateRequest(String title, String imageUrl, String description,
                                BigDecimal startPrice, LocalDateTime endAt) {
        this.title = title;
        this.imageUrl = imageUrl;
        this.description = description;
        this.startPrice = startPrice;
        this.endAt = endAt;
    }

    public String getTitle() { return title; }
    public String getImageUrl() { return imageUrl; }
    public String getDescription() { return description; }
    public BigDecimal getStartPrice() { return startPrice; }
    public LocalDateTime getEndAt() { return endAt; }
}
