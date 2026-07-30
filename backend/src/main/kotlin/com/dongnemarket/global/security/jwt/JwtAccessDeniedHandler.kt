package com.dongnemarket.global.security.jwt

import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.response.ErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/** 인가 실패(권한 없음) → 403 을 공통 [ErrorResponse](JSON) 으로 응답한다. */
@Component
class JwtAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val errorCode = ErrorCode.FORBIDDEN
        // Java 의 setStatus/setContentType/setCharacterEncoding 은 Kotlin 합성 프로퍼티로 대입한다.
        response.status = errorCode.status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        objectMapper.writeValue(response.writer, ErrorResponse.of(errorCode))
    }
}
