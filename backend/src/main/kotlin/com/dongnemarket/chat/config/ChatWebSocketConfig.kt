package com.dongnemarket.chat.config

import com.dongnemarket.chat.interceptor.ChatSubscribeInterceptor
import com.dongnemarket.global.websocket.JwtChannelInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * 채팅 실시간 인바운드 채널 설정. `@EnableWebSocketMessageBroker`는 공용 [com.dongnemarket.global.websocket.WebSocketConfig](경매)가 이미 켰고,
 * 스프링은 **모든** [WebSocketMessageBrokerConfigurer] 빈의 콜백을 실행하므로,
 * 이 별도 configurer로 브로커 설정을 건드리지 않고 인터셉터만 얹는다(경매 설정 무수정).
 * 1. [JwtChannelInterceptor] — CONNECT 프레임 JWT 인증(공용, principal 주입)
 * 2. [ChatSubscribeInterceptor] — 채팅방 토픽 SUBSCRIBE 참여자 인가
 */
@Configuration
class ChatWebSocketConfig(
    private val jwtChannelInterceptor: JwtChannelInterceptor,
    private val chatSubscribeInterceptor: ChatSubscribeInterceptor,
) : WebSocketMessageBrokerConfigurer {
    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(jwtChannelInterceptor, chatSubscribeInterceptor)
    }
}
