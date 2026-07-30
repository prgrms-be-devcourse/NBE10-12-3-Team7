package com.dongnemarket.global.response

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 공통 성공 응답 포맷.
 * ```
 * { "status": 200, "message": "...", "data": {} }
 * ```
 * 모든 도메인은 Controller에서 성공 응답을 이 타입으로 감싼다.
 *
 * Java 의 `static` 팩토리 메서드는 Kotlin 에 `static` 이 없어 [Companion] 으로 옮긴다.
 * 아직 Java 인 컨트롤러들이 `ApiResponse.success(...)` 를 그대로 호출할 수 있도록
 * `@JvmStatic` 을 붙인다(없으면 Java 에서 `ApiResponse.Companion.success(...)` 가 된다).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
class ApiResponse<T> private constructor(
    val status: Int,
    val message: String,
    val data: T?,
) {
    companion object {
        private const val DEFAULT_SUCCESS_MESSAGE = "요청이 성공적으로 처리되었습니다."

        /** 200 + 기본 메시지 + 데이터 */
        @JvmStatic
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(200, DEFAULT_SUCCESS_MESSAGE, data)

        /** 200 + 커스텀 메시지 + 데이터 */
        @JvmStatic
        fun <T> success(
            message: String,
            data: T,
        ): ApiResponse<T> = ApiResponse(200, message, data)

        /** 커스텀 status(예: 201) + 메시지 + 데이터 */
        @JvmStatic
        fun <T> success(
            status: Int,
            message: String,
            data: T,
        ): ApiResponse<T> = ApiResponse(status, message, data)

        /** 데이터 없는 성공 (예: 삭제) */
        @JvmStatic
        fun success(): ApiResponse<Void> = ApiResponse(200, DEFAULT_SUCCESS_MESSAGE, null)
    }
}
