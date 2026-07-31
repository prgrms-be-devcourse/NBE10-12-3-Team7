package com.dongnemarket.global.websocket

import com.dongnemarket.global.security.jwt.JwtTokenProvider
import io.jsonwebtoken.JwtException
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessagingException
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.stereotype.Component

/**
 * WebSocket(STOMP) 공용 인증 인터셉터.
 *
 * 브라우저 네이티브 WebSocket 은 핸드셰이크에 Authorization 헤더를 실을 수 없어
 * (그래서 SecurityConfig 의 `/ws` 하위 경로는 permitAll), 인증은 STOMP `CONNECT` 프레임에서 한다.
 *
 * CONNECT 프레임의 `Authorization: Bearer <token>` 을 검증해 `accessor.user = principal` 로
 * 세션에 principal 을 심는다. 이후 같은 세션의 SUBSCRIBE/SEND 프레임은 `accessor.user` 로 이를 재사용한다.
 *
 * 경매·채팅 등 모든 실시간 도메인이 공유하는 전역 인증(도메인별 인가는 각 도메인 인터셉터가 담당).
 */
@Component
class JwtChannelInterceptor(
    private val jwtTokenProvider: JwtTokenProvider,
) : ChannelInterceptor {
    override fun preSend(
        message: Message<*>,
        channel: MessageChannel,
    ): Message<*> {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
        if (accessor == null || StompCommand.CONNECT != accessor.command) {
            return message
        }

        val token = resolveToken(accessor)
        if (token == null || !jwtTokenProvider.validateToken(token)) {
            throw MessagingException(message, "WebSocket 인증에 실패했습니다.")
        }
        try {
            accessor.user = jwtTokenProvider.getAuthentication(token)
        } catch (e: JwtException) {
            // Refresh Token 오용 등: Access Token 이 아니면 인증 거부
            throw MessagingException(message, "WebSocket 인증에 실패했습니다.")
        } catch (e: IllegalArgumentException) {
            // Kotlin 에는 다중 catch(A | B) 가 없어 절을 나눈다. 공통 상위 타입으로 묶으면 범위가 넓어진다.
            throw MessagingException(message, "WebSocket 인증에 실패했습니다.")
        }
        return message
    }

    private fun resolveToken(accessor: StompHeaderAccessor): String? =
        accessor
            .getFirstNativeHeader(AUTH_HEADER)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.substring(BEARER_PREFIX.length)

    companion object {
        private const val AUTH_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }
}
