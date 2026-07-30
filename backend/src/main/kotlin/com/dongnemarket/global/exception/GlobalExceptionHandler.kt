package com.dongnemarket.global.exception

import com.dongnemarket.global.response.ErrorResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 전역 예외 처리기. 모든 예외를 공통 [ErrorResponse] 포맷으로 변환한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    /** 비즈니스 예외 (도메인 ErrorCode 기반) */
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ErrorResponse> {
        val errorCode = e.errorCode
        log.warn("BusinessException: [{}] {}", errorCode.code, e.message)
        // Throwable.message 는 Kotlin 에서 String? 다. BusinessException 은 두 생성자 모두
        // 메시지를 채우므로 실제로 null 이 되지 않지만, 타입상 필요한 폴백을 둔다.
        return ResponseEntity
            .status(errorCode.status)
            .body(ErrorResponse.of(errorCode, e.message ?: errorCode.message))
    }

    /** `@Valid` 검증 실패 */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        // Java: stream().findFirst().map(FieldError::getDefaultMessage).orElse(기본값)
        // Kotlin: firstOrNull()?.defaultMessage ?: 기본값 — defaultMessage 가 null 이어도 폴백된다.
        val message =
            e.bindingResult.fieldErrors
                .firstOrNull()
                ?.defaultMessage
                ?: ErrorCode.INVALID_INPUT_VALUE.message
        log.warn("Validation failed: {}", message)
        return ResponseEntity
            .status(ErrorCode.INVALID_INPUT_VALUE.status)
            .body(ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, message))
    }

    /** 인가 실패 (`@PreAuthorize` 등에서 컨트롤러 계층까지 전파된 경우) */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(ErrorCode.FORBIDDEN.status)
            .body(ErrorResponse.of(ErrorCode.FORBIDDEN))

    /** 그 외 처리되지 않은 예외 */
    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", e)
        return ResponseEntity
            .status(ErrorCode.INTERNAL_SERVER_ERROR.status)
            .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR))
    }

    companion object {
        /**
         * Java 의 `private static final Logger log` 대응.
         * Kotlin 에 static 이 없으므로 companion object 에 둔다.
         */
        private val log: Logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
