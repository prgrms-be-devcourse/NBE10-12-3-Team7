package com.dongnemarket.auction.controller;

import com.dongnemarket.auction.dto.AuctionStateResponse;
import com.dongnemarket.auction.dto.BidRequest;
import com.dongnemarket.auction.entity.Auction;
import com.dongnemarket.auction.service.AuctionService;
import com.dongnemarket.global.exception.BusinessException;
import com.dongnemarket.global.exception.ErrorCode;
import com.dongnemarket.global.response.ErrorResponse;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class AuctionBidController {

    private final AuctionService auctionService;
    private final SimpMessagingTemplate messagingTemplate;

    public AuctionBidController(AuctionService auctionService, SimpMessagingTemplate messagingTemplate) {
        this.auctionService = auctionService;
        this.messagingTemplate = messagingTemplate;
    }

    /** 입찰: SEND /app/auction/{id}/bid → 검증·저장 후 /topic/auction/{id} 로 새 상태 브로드캐스트. */
    @MessageMapping("/auction/{id}/bid")
    public void bid(@DestinationVariable Long id, @Payload BidRequest request, Principal principal) {
        if (request.getAmount() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Long bidderId = Long.valueOf(principal.getName()); // U1-d 인터셉터가 세션에 심은 memberId
        Auction updated = auctionService.placeBid(id, bidderId, request.getAmount());
        messagingTemplate.convertAndSend("/topic/auction/" + id, AuctionStateResponse.from(updated));
    }

    /** 입찰 실패(현재가 미달·충돌·종료 등)는 그 사용자에게만 통지: /user/queue/errors. */
    @MessageExceptionHandler(BusinessException.class)
    @SendToUser("/queue/errors")
    public ErrorResponse handleBusinessException(BusinessException e) {
        return ErrorResponse.of(e.getErrorCode());
    }
}
