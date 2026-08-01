package com.dongnemarket.auth.client

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.springframework.core.codec.CodecException
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

/**
 * 제공자 호출 예외를 공통 규칙으로 매핑한다: 4xx 는 인가 코드 관련 인증 실패, 그 외(5xx/네트워크/timeout/
 * 비정상 JSON/응답 크기 초과)는 모두 제공자 장애로 취급한다. 어느 경우든 제공자의 원문 응답 본문/에러
 * 메시지는 그대로 노출하지 않는다.
 *
 * 전환 규칙 — 원본은 package-private final class + private 생성자 + static 메서드였다.
 * Kotlin `internal object` 는 JVM 상 public 이 되므로, 원본의 접근 범위를 유지하기 위해
 * **package-private 유틸 클래스 형태를 그대로** 재현한다(`@JvmStatic` 으로 static 호출 경로 유지).
 * catch 순서와 예외 종류도 그대로다 — 순서가 바뀌면 매핑 결과가 달라진다.
 */
internal class OAuthClientErrorMapper private constructor() {
    companion object {
        @JvmStatic
        fun <T> call(action: Supplier<T>): T {
            try {
                return action.get()
            } catch (e: WebClientResponseException) {
                if (e.statusCode.is4xxClientError) {
                    throw BusinessException(ErrorCode.OAUTH_AUTHORIZATION_FAILED)
                }
                throw BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR)
            } catch (e: WebClientRequestException) {
                throw BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR)
            } catch (e: CodecException) {
                // 200 응답인데 JSON 이 깨진 경우 — 제공자가 정상적으로 응답하지 못한 것으로 취급한다.
                throw BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR)
            } catch (e: DataBufferLimitException) {
                // 응답이 설정한 최대 크기를 넘은 경우 — 위와 동일하게 취급한다.
                throw BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR)
            } catch (e: RuntimeException) {
                if (e.cause is TimeoutException) {
                    throw BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR)
                }
                throw e
            }
        }
    }
}
