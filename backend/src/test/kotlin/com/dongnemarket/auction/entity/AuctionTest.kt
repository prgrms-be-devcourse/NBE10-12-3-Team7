package com.dongnemarket.auction.entity

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [단위] Auction 엔티티 도메인 규칙 — placeBid(입찰가·상태 검증)와 close(종료 전이).
 */
class AuctionTest {
    /** 진행 중·현재가 10,000·종료 1시간 뒤 경매 (판매자 1) */
    private fun ongoingAuction(): Auction =
        Auction.create(1L, "test auction", null, null, BigDecimal.valueOf(10_000L), LocalDateTime.now().plusHours(1))

    @Test
    fun `placeBid - 현재가보다 높으면 최고가·최고입찰자가 갱신된다`() {
        val auction = ongoingAuction()

        auction.placeBid(2L, BigDecimal.valueOf(11_000L))

        assertThat(auction.currentPrice).isEqualByComparingTo(BigDecimal.valueOf(11_000L))
        assertThat(auction.highestBidderId).isEqualTo(2L)
    }

    @Test
    fun `placeBid - 현재가 이하로 입찰하면 BID_TOO_LOW 예외`() {
        val auction = ongoingAuction()

        val ex = assertThrows<BusinessException> { auction.placeBid(2L, BigDecimal.valueOf(10_000L)) }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.BID_TOO_LOW)
    }

    @Test
    fun `placeBid - 종료된 경매에 입찰하면 AUCTION_NOT_ONGOING 예외`() {
        val auction = ongoingAuction()
        auction.close()

        val ex = assertThrows<BusinessException> { auction.placeBid(2L, BigDecimal.valueOf(11_000L)) }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.AUCTION_NOT_ONGOING)
    }

    @Test
    fun `close - 진행 중 경매를 종료하면 ENDED로 전이된다`() {
        val auction = ongoingAuction()

        auction.close()

        assertThat(auction.status).isEqualTo(AuctionStatus.ENDED)
    }
}
