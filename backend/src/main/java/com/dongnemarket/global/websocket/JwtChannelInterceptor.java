package com.dongnemarket.global.websocket;

import com.dongnemarket.global.security.jwt.JwtTokenProvider;
import io.jsonwebtoken.JwtException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * WebSocket(STOMP) 공용 인증 인터셉터. 브라우저 네이티브 WebSocket은 핸드셰이크에 Authorization 헤더를 실을 수 없어
 * (그래서 SecurityConfig의 {@code /ws/**} 는 permitAll), 인증은 STOMP {@code CONNECT} 프레임에서 한다.
 * <p>CONNECT 프레임의 {@code Authorization: Bearer <token>} 을 검증해 {@code accessor.setUser(memberId principal)} 로
 * 세션에 principal 을 심는다. 이후 같은 세션의 SUBSCRIBE/SEND 프레임은 {@code accessor.getUser()} 로 이 principal 을 재사용한다.
 * <p>경매·채팅 등 모든 실시간 도메인이 공유하는 전역 인증(도메인별 인가는 각 도메인 인터셉터가 담당).
 */
@Component
public class JwtChannelInterceptor implements ChannelInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtChannelInterceptor(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String token = resolveToken(accessor);
        if (token == null || !jwtTokenProvider.validateToken(token)) {
            throw new MessagingException(message, "WebSocket 인증에 실패했습니다.");
        }
        try {
            Authentication authentication = jwtTokenProvider.getAuthentication(token);
            accessor.setUser(authentication);
        } catch (JwtException | IllegalArgumentException e) {
            // Refresh Token 오용 등: Access Token이 아니면 인증 거부
            throw new MessagingException(message, "WebSocket 인증에 실패했습니다.");
        }
        return message;
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        String bearer = accessor.getFirstNativeHeader(AUTH_HEADER);
        if (bearer != null && bearer.startsWith(BEARER_PREFIX)) {
            return bearer.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
