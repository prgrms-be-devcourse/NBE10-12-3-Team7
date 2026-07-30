package com.dongnemarket.chat.interceptor;

import com.dongnemarket.chat.service.ChatService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * 채팅방 토픽 구독 인가. {@code /topic/chat-rooms/{roomId}} 구독은 해당 방 참여자(구매자·판매자)만 허용한다.
 * <p>공유 인바운드 채널이라 경매 등 다른 도메인의 SUBSCRIBE 프레임도 이 인터셉터를 거치므로,
 * <b>채팅 목적지가 아니면 그대로 통과</b>시킨다(경매 구독을 깨지 않기 위함).
 * <p>인증(principal) 은 {@link com.dongnemarket.realtime.interceptor.JwtChannelInterceptor} 가 CONNECT 시점에 심어둔 것을 재사용한다.
 */
@Component
public class ChatSubscribeInterceptor implements ChannelInterceptor {

    private static final String CHAT_ROOM_TOPIC_PREFIX = "/topic/chat-rooms/";

    private final ChatService chatService;

    public ChatSubscribeInterceptor(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(CHAT_ROOM_TOPIC_PREFIX)) {
            return message; // 채팅방 토픽이 아니면(경매 등) 인가하지 않고 통과
        }

        Long roomId = parseRoomId(destination);
        Long memberId = requireMemberId(message, accessor.getUser());
        if (roomId == null || !chatService.isParticipant(memberId, roomId)) {
            throw new MessagingException(message, "채팅방 접근 권한이 없습니다.");
        }
        return message;
    }

    /**
     * 목적지에서 방 id를 추출한다. prefix 뒤 <b>첫 경로 세그먼트</b>만 파싱하므로
     * 방 토픽({@code .../{roomId}})과 읽음 영수증 서브토픽({@code .../{roomId}/read})을 함께 커버한다
     * (둘 다 같은 방 참여자만 구독 가능). 세그먼트가 숫자가 아니면 null(구독 거부).
     */
    private Long parseRoomId(String destination) {
        String raw = destination.substring(CHAT_ROOM_TOPIC_PREFIX.length());
        int slash = raw.indexOf('/');
        String roomSegment = slash >= 0 ? raw.substring(0, slash) : raw;
        try {
            return Long.valueOf(roomSegment);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long requireMemberId(Message<?> message, Principal user) {
        if (!(user instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof Long memberId)) {
            throw new MessagingException(message, "인증이 필요합니다.");
        }
        return memberId;
    }
}
