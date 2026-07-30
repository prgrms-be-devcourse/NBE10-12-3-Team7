package com.dongnemarket.realtime.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 실시간 경매 WebSocket(STOMP) 설정.
 * /ws 로 핸드셰이크, /app 로 들어온 메시지는 @MessageMapping 핸들러로,
 * /topic 구독자에게는 심플 브로커가 브로드캐스트한다.
 * <p>{@code /queue}는 user destination({@code convertAndSendToUser} → {@code /user/{id}/queue/**} →
 * {@code /queue/**-user{session}})용이다. 브로커에 {@code /queue}가 없으면 개인 큐 전송이 조용히 유실되므로
 * {@code /topic}과 함께 등록한다(안읽음 배지 등 사용자별 push).
 * <p>인바운드 채널 인터셉터(공용 JwtChannelInterceptor 등)는 별도 WebSocketMessageBrokerConfigurer 에서 얹는다
 * (스프링이 모든 configurer 콜백을 실행 — 이 설정은 브로커/엔드포인트만 담당).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws")
				.setAllowedOriginPatterns("*"); // 개발 단계: 모든 오리진 허용 (배포 시 좁힌다)
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.setApplicationDestinationPrefixes("/app"); // 클라 → 서버(@MessageMapping)
		registry.enableSimpleBroker("/topic", "/queue");    // /topic=브로드캐스트, /queue=user destination(개인 큐)
	}
}
