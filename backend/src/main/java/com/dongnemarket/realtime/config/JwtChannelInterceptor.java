package com.dongnemarket.realtime.config;

import com.dongnemarket.global.security.jwt.JwtTokenProvider;
import io.jsonwebtoken.JwtException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * STOMP CONNECT 프레임의 Authorization 헤더에서 JWT를 검증해 세션에 인증을 바인딩한다.
 * REST의 JwtAuthenticationFilter에 대응하는 메시지 채널 버전 —
 * 핸드셰이크(permitAll)를 통과한 뒤 실제 인증을 여기(CONNECT)에서 수행한다.
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
		StompHeaderAccessor accessor =
				MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

		// CONNECT 프레임에서만 인증한다(세션당 1회). 이후 프레임은 세션 Principal을 물려받음.
		if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
			String token = resolveToken(accessor.getFirstNativeHeader(AUTH_HEADER));
			Authentication authentication = jwtTokenProvider.getAuthentication(token); // 실패 시 예외 → ERROR 프레임
			accessor.setUser(authentication); // 이 WebSocket 세션에 Principal 바인딩
		}
		return message;
	}

	private String resolveToken(String header) {
		if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
			throw new JwtException("STOMP CONNECT에 유효한 Authorization 헤더가 없습니다.");
		}
		return header.substring(BEARER_PREFIX.length());
	}
}
