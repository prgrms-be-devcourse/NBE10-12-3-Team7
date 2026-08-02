package com.dongnemarket.auth.controller

import com.dongnemarket.auth.dto.PasswordResetConfirmRequest
import com.dongnemarket.auth.dto.PasswordResetRequest
import com.dongnemarket.auth.service.PasswordResetService
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 전환 규칙 — `@Valid @RequestBody` 파라미터는 non-null 로 유지한다([AuthController] 와 동일).
 *
 * 반환 제네릭이 `ApiResponse<Void?>` 인 이유 — 원본 Java 는 `ApiResponse.success(message, null)` 로
 * `ApiResponse<Void>` 를 만들었지만, Kotlin 제네릭에서 `T = Void`(non-null) 에는 null 을 넘길 수 없다.
 * `Void?` 는 JVM generic signature 에서 `Ljava/lang/Void;` 로 동일하게 소거되므로(nullability 는
 * `@Metadata` 에만 실린다) Java 원본과 시그니처·응답 JSON(`data` 필드 생략)이 같다.
 */
@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/auth/password-resets")
class PasswordResetController(
    private val passwordResetService: PasswordResetService,
) {
    @Operation(
        summary = "비밀번호 재설정 요청",
        description =
            "이메일로 비밀번호 재설정 토큰을 발송한다. 계정 존재 여부를 노출하지 않기 위해 " +
                "가입 여부와 무관하게 항상 동일한 응답을 반환한다.",
    )
    @PostMapping
    fun requestReset(
        @Valid @RequestBody request: PasswordResetRequest,
    ): ResponseEntity<ApiResponse<Void?>> {
        passwordResetService.requestReset(request)
        return ResponseEntity.ok(ApiResponse.success(REQUEST_MESSAGE, null))
    }

    @Operation(
        summary = "비밀번호 재설정 확인",
        description =
            "재설정 토큰을 확인해 새 비밀번호로 변경한다. 토큰이 유효하지 않거나 이미 사용됐으면 400 " +
                "INVALID_RESET_TOKEN, 만료됐으면 400 EXPIRED_RESET_TOKEN을 반환한다. 성공 시 기존 Refresh Token은 삭제된다.",
    )
    @PostMapping("/confirm")
    fun confirmReset(
        @Valid @RequestBody request: PasswordResetConfirmRequest,
    ): ResponseEntity<ApiResponse<Void?>> {
        passwordResetService.confirmReset(request)
        return ResponseEntity.ok(ApiResponse.success("비밀번호가 재설정되었습니다.", null))
    }

    companion object {
        private const val REQUEST_MESSAGE = "해당 이메일로 가입된 계정이 있다면 비밀번호 재설정 메일을 발송했습니다."
    }
}
