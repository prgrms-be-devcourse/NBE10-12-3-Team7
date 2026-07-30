package com.dongnemarket.auction.service;

import com.dongnemarket.auction.dto.AuctionCreateRequest;
import com.dongnemarket.auction.dto.AuctionResponse;
import com.dongnemarket.auction.entity.Auction;
import com.dongnemarket.auction.repository.AuctionRepository;
import com.dongnemarket.global.exception.BusinessException;
import com.dongnemarket.global.exception.ErrorCode;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class AuctionService {

    private final AuctionRepository auctionRepository;

    public AuctionService(AuctionRepository auctionRepository) {
        this.auctionRepository = auctionRepository;
    }

    /** 경매 등록: 인증된 판매자(sellerId) 명의로 진행 중 경매를 만든다. */
    @Transactional
    public AuctionResponse create(Long sellerId, AuctionCreateRequest request) {
        Auction auction = Auction.create(
                sellerId, request.getTitle(), request.getImageUrl(), request.getDescription(),
                request.getStartPrice(), request.getEndAt());
        return AuctionResponse.from(auctionRepository.save(auction));
    }

    /**
     * 입찰 처리: 경매를 조회해 최고가를 갱신한다.
     * 동시 입찰은 @Version 낙관적 락으로 걸러내고, 진 쪽은 재입찰하도록 충돌 예외로 변환한다.
     */
    @Transactional
    public Auction placeBid(Long auctionId, Long bidderId, BigDecimal amount) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

        auction.placeBid(bidderId, amount); // 진행중·현재가 검증 + 최고가 갱신 (엔티티)

        try {
            // save가 아니라 saveAndFlush: 낙관적 락 UPDATE를 지금(트랜잭션 안) 실행해
            // 충돌 예외를 여기서 잡는다. (save는 커밋 시점=메서드 밖에서 터져 못 잡음)
            return auctionRepository.saveAndFlush(auction);
        } catch (OptimisticLockingFailureException e) {
            throw new BusinessException(ErrorCode.AUCTION_BID_CONFLICT);
        }
    }
}
