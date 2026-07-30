package com.dongnemarket.global.response

import com.dongnemarket.global.exception.ErrorCode
import java.time.LocalDateTime

/**
 * 공통 에러 응답 포맷.
 * ```
 * { "status": 404, "error": "PRODUCT_NOT_FOUND", "message": "...", "timestamp": "2026-06-17T12:00:00" }
 * ```
 * `error` 는 ErrorCode 의 이름(enum 상수명)이다.
 */
class ErrorResponse private constructor(
    val status: Int,
    val error: String,
    val message: String,
) {
    /**
     * Java 는 생성자 본문에서 `this.timestamp = LocalDateTime.now()` 로 채웠다.
     * Kotlin 은 프로퍼티 초기화식이 생성 시점에 실행되므로 같은 결과다.
     */
    val timestamp: LocalDateTime = LocalDateTime.now()

    companion object {
        @JvmStatic
        fun of(errorCode: ErrorCode): ErrorResponse = ErrorResponse(errorCode.status, errorCode.name, errorCode.message)

        @JvmStatic
        fun of(
            errorCode: ErrorCode,
            message: String,
        ): ErrorResponse = ErrorResponse(errorCode.status, errorCode.name, message)
    }
}
