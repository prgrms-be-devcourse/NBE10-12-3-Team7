package com.dongnemarket.auction.service

import com.dongnemarket.auction.entity.Auction
import com.dongnemarket.auction.repository.AuctionRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.OptimisticLockingFailureException
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional

/**
 * [단위] AuctionService.placeBid — 서비스 고유 로직만 검증(입찰가·상태 검증은 엔티티 단위테스트가 담당).
 *  - 경매 부재 시 AUCTION_NOT_FOUND
 *  - 동시 입찰로 낙관적 락 충돌(OptimisticLockingFailureException) 시 AUCTION_BID_CONFLICT 로 변환
 */
@ExtendWith(MockitoExtension::class)
class AuctionServiceTest {
    @Mock
    lateinit var auctionRepository: AuctionRepository

    @InjectMocks
    lateinit var auctionService: AuctionService

    private fun ongoingAuction(): Auction =
        Auction.create(1L, "test auction", null, null, BigDecimal.valueOf(10_000L), LocalDateTime.now().plusHours(1))

    @Test
    fun `placeBid - 경매가 없으면 AUCTION_NOT_FOUND`() {
        given(auctionRepository.findById(99L)).willReturn(Optional.empty())

        val ex =
            assertThrows<BusinessException> {
                auctionService.placeBid(99L, 2L, BigDecimal.valueOf(11_000L))
            }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.AUCTION_NOT_FOUND)
    }

    @Test
    fun `placeBid - 동시 입찰로 낙관적 락 충돌 시 AUCTION_BID_CONFLICT 로 변환`() {
        val auction = ongoingAuction()
        given(auctionRepository.findById(1L)).willReturn(Optional.of(auction))
        given(auctionRepository.saveAndFlush(auction))
            .willThrow(OptimisticLockingFailureException("version conflict"))

        val ex =
            assertThrows<BusinessException> {
                auctionService.placeBid(1L, 2L, BigDecimal.valueOf(11_000L))
            }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.AUCTION_BID_CONFLICT)
    }
}
