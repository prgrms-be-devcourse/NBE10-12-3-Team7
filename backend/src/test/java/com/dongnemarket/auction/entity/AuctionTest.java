package com.dongnemarket.auction.entity;

import com.dongnemarket.global.exception.BusinessException;
import com.dongnemarket.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [단위] Auction 엔티티 도메인 규칙 — placeBid(입찰가·상태 검증)와 close(종료 전이).
 */
class AuctionTest {

    private Auction ongoingAuction() {
        // 진행 중·현재가 10,000·종료 1시간 뒤 경매 (판매자 1)
        return Auction.create(1L, "test auction", null, null,
                BigDecimal.valueOf(10_000), LocalDateTime.now().plusHours(1));
    }

    @Test
    @DisplayName("placeBid(): 현재가보다 높으면 최고가·최고입찰자가 갱신된다")
    void placeBid_higher_success() {
        Auction auction = ongoingAuction();

        auction.placeBid(2L, BigDecimal.valueOf(11_000));

        assertThat(auction.getCurrentPrice()).isEqualByComparingTo(BigDecimal.valueOf(11_000));
        assertThat(auction.getHighestBidderId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("placeBid(): 현재가 이하로 입찰하면 BID_TOO_LOW 예외")
    void placeBid_notHigher_throws() {
        Auction auction = ongoingAuction();

        assertThatThrownBy(() -> auction.placeBid(2L, BigDecimal.valueOf(10_000)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BID_TOO_LOW);
    }

    @Test
    @DisplayName("placeBid(): 종료된 경매에 입찰하면 AUCTION_NOT_ONGOING 예외")
    void placeBid_whenEnded_throws() {
        Auction auction = ongoingAuction();
        auction.close();

        assertThatThrownBy(() -> auction.placeBid(2L, BigDecimal.valueOf(11_000)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUCTION_NOT_ONGOING);
    }

    @Test
    @DisplayName("close(): 진행 중 경매를 종료하면 ENDED로 전이된다")
    void close_fromOngoing_success() {
        Auction auction = ongoingAuction();

        auction.close();

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ENDED);
    }
}
