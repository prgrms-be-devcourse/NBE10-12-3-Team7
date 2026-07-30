package com.dongnemarket.auction.service;

import com.dongnemarket.auction.entity.Auction;
import com.dongnemarket.auction.repository.AuctionRepository;
import com.dongnemarket.global.exception.BusinessException;
import com.dongnemarket.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * [단위] AuctionService.placeBid — 서비스 고유 로직만 검증(입찰가·상태 검증은 엔티티 단위테스트가 담당).
 *  - 경매 부재 시 AUCTION_NOT_FOUND
 *  - 동시 입찰로 낙관적 락 충돌(OptimisticLockingFailureException) 시 AUCTION_BID_CONFLICT 로 변환
 */
@ExtendWith(MockitoExtension.class)
class AuctionServiceTest {

    @Mock
    AuctionRepository auctionRepository;

    @InjectMocks
    AuctionService auctionService;

    private Auction ongoingAuction() {
        return Auction.create(1L, "test auction", null, null,
                BigDecimal.valueOf(10_000), LocalDateTime.now().plusHours(1));
    }

    @Test
    @DisplayName("placeBid(): 경매가 없으면 AUCTION_NOT_FOUND")
    void placeBid_auctionNotFound() {
        given(auctionRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> auctionService.placeBid(99L, 2L, BigDecimal.valueOf(11_000)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUCTION_NOT_FOUND);
    }

    @Test
    @DisplayName("placeBid(): 동시 입찰로 낙관적 락 충돌 시 AUCTION_BID_CONFLICT 로 변환")
    void placeBid_optimisticLockConflict() {
        Auction auction = ongoingAuction();
        given(auctionRepository.findById(1L)).willReturn(Optional.of(auction));
        given(auctionRepository.saveAndFlush(auction))
                .willThrow(new OptimisticLockingFailureException("version conflict"));

        assertThatThrownBy(() -> auctionService.placeBid(1L, 2L, BigDecimal.valueOf(11_000)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUCTION_BID_CONFLICT);
    }
}
