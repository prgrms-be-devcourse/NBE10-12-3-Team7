package com.dongnemarket.auth.controller

import com.dongnemarket.auth.dto.EmailVerificationConfirmRequest
import com.dongnemarket.auth.dto.EmailVerificationConfirmResponse
import com.dongnemarket.auth.dto.EmailVerificationRequest
import com.dongnemarket.auth.dto.EmailVerificationResponse
import com.dongnemarket.auth.service.EmailVerificationService
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 전환 규칙 — `@Valid @RequestBody` 파라미터는 non-null 로 유지한다. nullable 로 두면 Spring 이
 * body 를 선택 사항으로 해석해 원본의 "body 누락 → 400" 계약이 바뀐다([AuthController] 와 동일).
 */
@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/auth/email-verifications")
class EmailVerificationController(
    private val emailVerificationService: EmailVerificationService,
) {
    @Operation(summary = "이메일 인증 코드 발송", description = "회원가입 전 이메일로 인증 코드를 발송한다. 이미 가입된 이메일이면 409, 60초 이내 재요청이면 429를 반환한다.")
    @PostMapping
    fun requestVerification(
        @Valid @RequestBody request: EmailVerificationRequest,
    ): ResponseEntity<ApiResponse<EmailVerificationResponse>> {
        val response = emailVerificationService.requestVerification(request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(HttpStatus.CREATED.value(), "인증 코드가 발송되었습니다.", response))
    }

    @Operation(
        summary = "이메일 인증 코드 확인",
        description = "발송된 인증 코드를 확인해 인증을 완료한다. 요청 이력이 없으면 404, 코드 불일치는 400, 코드 만료는 400을 반환한다. 이미 인증 완료된 건은 멱등하게 성공을 반환한다.",
    )
    @PostMapping("/confirm")
    fun confirmVerification(
        @Valid @RequestBody request: EmailVerificationConfirmRequest,
    ): ResponseEntity<ApiResponse<EmailVerificationConfirmResponse>> {
        val response = emailVerificationService.confirmVerification(request)
        return ResponseEntity.ok(ApiResponse.success("이메일 인증이 완료되었습니다.", response))
    }
}
