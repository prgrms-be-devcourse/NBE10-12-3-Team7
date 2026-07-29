package com.dongnemarket.realtime.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 실시간 경매 WebSocket(STOMP) 설정.
 * /ws 로 핸드셰이크, /app 로 들어온 메시지는 @MessageMapping 핸들러로,
 * /topic 구독자에게는 심플 브로커가 브로드캐스트한다.
 * 들어오는 프레임은 JwtChannelInterceptor가 CONNECT 시 인증한다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final JwtChannelInterceptor jwtChannelInterceptor;

	public WebSocketConfig(JwtChannelInterceptor jwtChannelInterceptor) {
		this.jwtChannelInterceptor = jwtChannelInterceptor;
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws")
				.setAllowedOriginPatterns("*"); // 개발 단계: 모든 오리진 허용 (배포 시 좁힌다)
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.setApplicationDestinationPrefixes("/app"); // 클라 → 서버(@MessageMapping)
		registry.enableSimpleBroker("/topic");              // 서버 → 구독 클라 브로드캐스트
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(jwtChannelInterceptor); // 들어오는 프레임에 JWT 인증 인터셉터 장착
	}
}
