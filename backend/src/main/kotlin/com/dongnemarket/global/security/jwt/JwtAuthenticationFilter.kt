package com.dongnemarket.global.security.jwt

import com.dongnemarket.global.exception.ErrorCode
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 요청 헤더의 Bearer 토큰을 검증해 SecurityContext 에 인증을 등록한다.
 *
 * SecurityConfig 에서 직접 생성해 필터 체인에 등록한다
 * (빈으로 두면 서블릿에 이중 등록되므로 `@Component` 미사용).
 */
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = resolveToken(request)
        if (token != null) {
            // Kotlin 에는 Java 의 다중 catch(`catch (A | B e)`)가 없다.
            // 같은 처리를 하는 예외라도 catch 절을 나눠 쓴다(공통 상위 타입을 잡으면 범위가 넓어진다).
            try {
                val authentication = jwtTokenProvider.getAuthentication(token)
                SecurityContextHolder.getContext().authentication = authentication
            } catch (e: JwtException) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.INVALID_TOKEN) // 토큰은 왔는데 깨짐
            } catch (e: IllegalArgumentException) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.INVALID_TOKEN)
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val bearer = request.getHeader(AUTH_HEADER)
        if (!bearer.isNullOrBlank() && bearer.startsWith(BEARER_PREFIX)) {
            return bearer.substring(BEARER_PREFIX.length)
        }
        return null
    }

    companion object {
        private const val AUTH_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "

        /** JwtAuthenticationEntryPoint 가 읽어 401 응답의 ErrorCode 를 가른다. */
        const val AUTH_ERROR_ATTRIBUTE = "authError"
    }
}
