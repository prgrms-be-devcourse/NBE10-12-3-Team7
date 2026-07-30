package com.dongnemarket.global.security.jwt

import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.response.ErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/** 미인증 접근 → 401 을 공통 [ErrorResponse](JSON) 으로 응답한다. */
@Component
class JwtAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        // Java 의 패턴 매칭(`authError instanceof ErrorCode ec`)은 Kotlin 에서 `is` + 스마트 캐스트로 대체된다.
        // if 가 식(expression)이라 그대로 값을 반환한다.
        val authError = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE)
        val errorCode = if (authError is ErrorCode) authError else ErrorCode.UNAUTHORIZED
        response.status = errorCode.status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        objectMapper.writeValue(response.writer, ErrorResponse.of(errorCode))
    }
}
