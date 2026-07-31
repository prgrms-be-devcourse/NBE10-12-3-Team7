package com.dongnemarket.auction.service

import com.dongnemarket.auction.dto.AuctionCreateRequest
import com.dongnemarket.auction.dto.AuctionResponse
import com.dongnemarket.auction.dto.AuctionStateResponse
import com.dongnemarket.auction.entity.Auction
import com.dongnemarket.auction.entity.AuctionStatus
import com.dongnemarket.auction.repository.AuctionRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime

@Service
class AuctionService(
    private val auctionRepository: AuctionRepository,
) {
    /**
     * 경매 등록: 인증된 판매자(sellerId) 명의로 진행 중 경매를 만든다.
     *
     * request 의 세 필드는 nullable 이지만 컨트롤러의 `@Valid` 가 null 을 걸러낸다
     * (근거는 AuctionCreateRequestValidationTest 에 고정돼 있다).
     */
    @Transactional
    fun create(
        sellerId: Long,
        request: AuctionCreateRequest,
    ): AuctionResponse {
        val auction =
            Auction.create(
                sellerId,
                requireNotNull(request.title),
                request.imageUrl,
                request.description,
                requireNotNull(request.startPrice),
                requireNotNull(request.endAt),
            )
        return AuctionResponse.from(auctionRepository.save(auction))
    }

    /** 목록 조회(최신순). */
    @Transactional(readOnly = true)
    fun getAuctions(): List<AuctionResponse> = auctionRepository.findAllByOrderByCreatedAtDesc().map(AuctionResponse::from)

    /** 상세 조회: 새 구독자가 초기 현재가를 알기 위해서도 사용. */
    @Transactional(readOnly = true)
    fun getAuction(auctionId: Long): AuctionResponse {
        val auction =
            auctionRepository
                .findById(auctionId)
                .orElseThrow { BusinessException(ErrorCode.AUCTION_NOT_FOUND) }
        return AuctionResponse.from(auction)
    }

    /**
     * 입찰 처리: 경매를 조회해 최고가를 갱신한다.
     * 동시 입찰은 @Version 낙관적 락으로 걸러내고, 진 쪽은 재입찰하도록 충돌 예외로 변환한다.
     */
    @Transactional
    fun placeBid(
        auctionId: Long,
        bidderId: Long,
        amount: BigDecimal,
    ): Auction {
        val auction =
            auctionRepository
                .findById(auctionId)
                .orElseThrow { BusinessException(ErrorCode.AUCTION_NOT_FOUND) }

        auction.placeBid(bidderId, amount) // 진행중·현재가 검증 + 최고가 갱신 (엔티티)

        return try {
            // save 가 아니라 saveAndFlush: 낙관적 락 UPDATE 를 지금(트랜잭션 안) 실행해
            // 충돌 예외를 여기서 잡는다. (save 는 커밋 시점=메서드 밖에서 터져 못 잡음)
            auctionRepository.saveAndFlush(auction)
        } catch (e: OptimisticLockingFailureException) {
            throw BusinessException(ErrorCode.AUCTION_BID_CONFLICT)
        }
    }

    /** 종료시각이 지난 진행 중 경매를 모두 ENDED로 전이하고, 그 최종 상태를 반환한다(스케줄러가 호출). */
    @Transactional
    fun closeExpiredAuctions(): List<AuctionStateResponse> =
        auctionRepository
            .findByStatusAndEndAtBefore(AuctionStatus.ONGOING, LocalDateTime.now())
            .map { auction ->
                auction.close()
                AuctionStateResponse.from(auction)
            }
}
