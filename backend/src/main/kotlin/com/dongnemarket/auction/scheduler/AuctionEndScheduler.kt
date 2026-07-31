package com.dongnemarket.auction.scheduler

import com.dongnemarket.auction.service.AuctionService
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 고정 종료시각이 지난 경매를 주기적으로 종료 처리하고, 종료된 최종 상태를
 * 해당 경매 토픽 구독자에게 브로드캐스트한다(낙찰 확정 통지).
 */
@Component
class AuctionEndScheduler(
    private val auctionService: AuctionService,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    @Scheduled(fixedDelay = 5000) // 5초마다 만료 경매 확인
    fun closeExpiredAuctions() {
        auctionService.closeExpiredAuctions().forEach { state ->
            messagingTemplate.convertAndSend("/topic/auction/${state.auctionId}", state)
        }
    }
}
