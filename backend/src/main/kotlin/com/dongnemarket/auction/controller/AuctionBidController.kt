package com.dongnemarket.auction.controller

import com.dongnemarket.auction.dto.AuctionStateResponse
import com.dongnemarket.auction.dto.BidRequest
import com.dongnemarket.auction.service.AuctionService
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.response.ErrorResponse
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageExceptionHandler
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.annotation.SendToUser
import org.springframework.stereotype.Controller
import java.security.Principal

@Controller
class AuctionBidController(
    private val auctionService: AuctionService,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    /** 입찰: SEND /app/auction/{id}/bid → 검증·저장 후 /topic/auction/{id} 로 새 상태 브로드캐스트. */
    @MessageMapping("/auction/{id}/bid")
    fun bid(
        @DestinationVariable id: Long,
        @Payload request: BidRequest,
        principal: Principal,
    ) {
        // 원본의 `if (request.getAmount() == null) throw ...` 가 엘비스 한 줄로 합쳐진다.
        // 통과하면 amount 는 non-null 로 확정되어 placeBid(amount: BigDecimal) 에 그대로 넘어간다.
        val amount = request.amount ?: throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        val bidderId = principal.name.toLong() // U1-d 인터셉터가 세션에 심은 memberId
        val updated = auctionService.placeBid(id, bidderId, amount)
        messagingTemplate.convertAndSend("/topic/auction/$id", AuctionStateResponse.from(updated))
    }

    /** 입찰 실패(현재가 미달·충돌·종료 등)는 그 사용자에게만 통지: /user/queue/errors. */
    @MessageExceptionHandler(BusinessException::class)
    @SendToUser("/queue/errors")
    fun handleBusinessException(e: BusinessException): ErrorResponse = ErrorResponse.of(e.errorCode)
}
