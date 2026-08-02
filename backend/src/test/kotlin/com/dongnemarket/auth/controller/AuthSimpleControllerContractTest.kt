package com.dongnemarket.auth.controller

import com.dongnemarket.auth.dto.EmailVerificationConfirmRequest
import com.dongnemarket.auth.dto.EmailVerificationConfirmResponse
import com.dongnemarket.auth.dto.EmailVerificationRequest
import com.dongnemarket.auth.dto.EmailVerificationResponse
import com.dongnemarket.auth.dto.PasswordResetConfirmRequest
import com.dongnemarket.auth.dto.PasswordResetRequest
import com.dongnemarket.auth.service.EmailVerificationService
import com.dongnemarket.auth.service.PasswordResetService
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * 단순 controller 2개(이메일 인증·비밀번호 재설정)의 **상태코드·메시지·data·위임 횟수** 계약을 고정한다.
 *
 * 특히 비밀번호 재설정 요청의 응답 메시지는 **계정 존재 여부 비노출 보안 계약**이다 — 조건에 따라
 * 문구·상태코드가 달라지면 안 되고, `data` 는 항상 생략(null + `NON_NULL`)이어야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSimpleControllerContractTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var emailVerificationService: EmailVerificationService

    @MockitoBean
    lateinit var passwordResetService: PasswordResetService

    @Nested
    @DisplayName("이메일 인증 2개")
    inner class EmailVerification {
        @Test
        fun `요청은 201 과 정확한 메시지를 반환하고 service 를 1회 호출한다`() {
            `when`(emailVerificationService.requestVerification(anyObj(EmailVerificationRequest::class.java)))
                .thenReturn(EmailVerificationResponse("test@example.com", LocalDateTime.of(2026, 1, 1, 0, 5)))

            mockMvc
                .perform(
                    post("/api/auth/email-verifications")
                        .contentType("application/json")
                        .content("""{"email":"test@example.com"}"""),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.message").value("인증 코드가 발송되었습니다."))
                .andExpect(jsonPath("$.data.email").value("test@example.com"))

            verify(emailVerificationService, times(1)).requestVerification(anyObj(EmailVerificationRequest::class.java))
        }

        @Test
        fun `확인은 200 과 정확한 메시지를 반환하고 service 를 1회 호출한다`() {
            `when`(emailVerificationService.confirmVerification(anyObj(EmailVerificationConfirmRequest::class.java)))
                .thenReturn(EmailVerificationConfirmResponse("test@example.com", true))

            mockMvc
                .perform(
                    post("/api/auth/email-verifications/confirm")
                        .contentType("application/json")
                        .content("""{"email":"test@example.com","code":"test-verification-code"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("이메일 인증이 완료되었습니다."))
                .andExpect(jsonPath("$.data.verified").value(true))

            verify(emailVerificationService, times(1)).confirmVerification(anyObj(EmailVerificationConfirmRequest::class.java))
        }
    }

    @Nested
    @DisplayName("비밀번호 재설정 2개")
    inner class PasswordReset {
        @Test
        fun `요청은 200 과 항상 같은 중립 메시지를 반환하고 data 는 생략된다`() {
            mockMvc
                .perform(
                    post("/api/auth/password-resets")
                        .contentType("application/json")
                        .content("""{"email":"test@example.com"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("해당 이메일로 가입된 계정이 있다면 비밀번호 재설정 메일을 발송했습니다."))
                .andExpect(jsonPath("$.data").doesNotExist())

            verify(passwordResetService, times(1)).requestReset(anyObj(PasswordResetRequest::class.java))
        }

        @Test
        fun `확인은 200 과 정확한 메시지를 반환하고 data 는 생략된다`() {
            mockMvc
                .perform(
                    post("/api/auth/password-resets/confirm")
                        .contentType("application/json")
                        .content("""{"token":"test-reset-token","newPassword":"Password123!"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("비밀번호가 재설정되었습니다."))
                .andExpect(jsonPath("$.data").doesNotExist())

            verify(passwordResetService, times(1)).confirmReset(anyObj(PasswordResetConfirmRequest::class.java))
        }
    }

    companion object {
        /**
         * Mockito 매처는 null 을 반환하는데, Kotlin **non-null 파라미터** 자리에 넣으면 호출부
         * intrinsic null 검사("must not be null")가 매처 등록 전에 터진다. 매처를 등록한 뒤
         * 타입만 맞춘 값을 돌려주는 표준 우회다(6단계 테스트와 같은 방식).
         */
        @Suppress("UNCHECKED_CAST")
        fun <T> anyObj(type: Class<T>): T {
            any(type)
            return null as T
        }
    }
}
