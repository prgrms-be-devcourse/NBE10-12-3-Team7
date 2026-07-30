package com.dongnemarket.auction.scheduler;

import com.dongnemarket.auction.dto.AuctionStateResponse;
import com.dongnemarket.auction.service.AuctionService;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 고정 종료시각이 지난 경매를 주기적으로 종료 처리하고, 종료된 최종 상태를
 * 해당 경매 토픽 구독자에게 브로드캐스트한다(낙찰 확정 통지).
 */
@Component
public class AuctionEndScheduler {

    private final AuctionService auctionService;
    private final SimpMessagingTemplate messagingTemplate;

    public AuctionEndScheduler(AuctionService auctionService, SimpMessagingTemplate messagingTemplate) {
        this.auctionService = auctionService;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedDelay = 5000) // 5초마다 만료 경매 확인
    public void closeExpiredAuctions() {
        List<AuctionStateResponse> closed = auctionService.closeExpiredAuctions();
        for (AuctionStateResponse state : closed) {
            messagingTemplate.convertAndSend("/topic/auction/" + state.getAuctionId(), state);
        }
    }
}
