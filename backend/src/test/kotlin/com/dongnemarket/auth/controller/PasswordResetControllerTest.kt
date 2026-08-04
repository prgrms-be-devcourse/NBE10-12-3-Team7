package com.dongnemarket.auth.controller

import com.dongnemarket.auth.entity.EmailVerification
import com.dongnemarket.auth.mail.EmailSender
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.auth.repository.InMemoryPasswordResetTokenRepository
import com.dongnemarket.member.repository.MemberAgreementRepository
import com.dongnemarket.member.repository.MemberRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * 실제 SMTP 발송 대신 [EmailSender]를 Mock으로 대체해 토큰 생성/저장/쿨다운/확인 로직만 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var emailVerificationRepository: EmailVerificationRepository

    @Autowired
    lateinit var passwordResetTokenRepository: InMemoryPasswordResetTokenRepository

    @Autowired
    lateinit var memberAgreementRepository: MemberAgreementRepository

    @MockitoBean
    lateinit var emailSender: EmailSender

    @AfterEach
    fun cleanUp() {
        passwordResetTokenRepository.clear()
        emailVerificationRepository.deleteAll()
        memberAgreementRepository.deleteAll()
        memberRepository.deleteAll()
    }

    private fun verifyEmail(email: String) {
        emailVerificationRepository.save(EmailVerification.verified(email, LocalDateTime.now()))
    }

    private fun signup(
        email: String,
        password: String,
        nickname: String,
    ) {
        verifyEmail(email)
        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"email":"$email","password":"$password","nickname":"$nickname","termsAgreed":true,"personalInfoCollectionAgreed":true}""",
                ),
        )
    }

    // ===== POST /api/auth/password-resets =====

    @Test
    @DisplayName("가입된 이메일로 요청하면 200과 중립 메시지를 반환하고, 응답 body에는 토큰을 전혀 포함하지 않는다")
    fun requestReset_registeredEmail_returns200AndSends() {
        signup("reset-target@example.com", "password123!", "resetTarget")

        mockMvc
            .perform(
                post("/api/auth/password-resets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "reset-target@example.com" }"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data").doesNotExist())

        verify(emailSender).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("가입되지 않은 이메일로 요청해도 200과 동일한 중립 메시지를 반환하고 발송하지 않는다(계정 존재 노출 방지)")
    fun requestReset_unregisteredEmail_returns200SameMessageWithoutSending() {
        mockMvc
            .perform(
                post("/api/auth/password-resets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "never-signed-up@example.com" }"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.message").value("해당 이메일로 가입된 계정이 있다면 비밀번호 재설정 메일을 발송했습니다."))

        verify(emailSender, never()).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("이메일 형식이 올바르지 않으면 400과 INVALID_INPUT_VALUE를 반환한다")
    fun requestReset_invalidEmailFormat_returns400() {
        mockMvc
            .perform(
                post("/api/auth/password-resets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "not-an-email" }"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    // ===== POST /api/auth/password-resets/confirm =====

    /** DB에는 해시만 저장되므로, Mock으로 대체한 EmailSender에 실제로 전달된 메일 본문의 링크에서 원문 토큰을 꺼낸다. */
    private fun requestResetAndGetToken(email: String): String {
        mockMvc.perform(
            post("/api/auth/password-resets")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "email": "$email" }"""),
        )

        val bodyCaptor: ArgumentCaptor<String> = ArgumentCaptor.forClass(String::class.java)
        verify(emailSender).send(anyString(), anyString(), cap(bodyCaptor))
        val body = bodyCaptor.value
        val index = body.indexOf("?token=")
        return body.substring(index + "?token=".length).split("\\s".toRegex(), 2)[0]
    }

    @Test
    @DisplayName("유효한 토큰으로 확인하면 200을 반환하고 새 비밀번호로 로그인할 수 있다")
    fun confirmReset_validToken_success() {
        signup("reset-confirm@example.com", "password123!", "resetConfirm")
        val token = requestResetAndGetToken("reset-confirm@example.com")

        mockMvc
            .perform(
                post("/api/auth/password-resets/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "token": "$token", "newPassword": "newPassword123!" }"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data").doesNotExist())

        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "reset-confirm@example.com", "password": "newPassword123!" }"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accessToken").exists())
    }

    @Test
    @DisplayName("같은 토큰으로 다시 확인하면(1회용 소진) 400과 INVALID_RESET_TOKEN을 반환한다")
    fun confirmReset_reusedToken_returns400() {
        signup("reset-reuse@example.com", "password123!", "resetReuse")
        val token = requestResetAndGetToken("reset-reuse@example.com")
        mockMvc.perform(
            post("/api/auth/password-resets/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "token": "$token", "newPassword": "newPassword123!" }"""),
        )

        mockMvc
            .perform(
                post("/api/auth/password-resets/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "token": "$token", "newPassword": "anotherPassword123!" }"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_RESET_TOKEN"))
    }

    @Test
    @DisplayName("존재하지 않는 토큰으로 확인하면 400과 INVALID_RESET_TOKEN을 반환한다")
    fun confirmReset_unknownToken_returns400() {
        mockMvc
            .perform(
                post("/api/auth/password-resets/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "token": "unknown-token", "newPassword": "newPassword123!" }"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_RESET_TOKEN"))
    }

    @Test
    @DisplayName("새 비밀번호가 정책에 맞지 않으면 400과 INVALID_INPUT_VALUE를 반환한다")
    fun confirmReset_invalidNewPasswordFormat_returns400() {
        mockMvc
            .perform(
                post("/api/auth/password-resets/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "token": "any-token", "newPassword": "nospecialchar123" }"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    companion object {
        /**
         * Mockito 매처(captor 포함)는 null 을 반환하는데, Kotlin **non-null 파라미터** 자리에 넣으면 호출부
         * intrinsic null 검사("must not be null")가 매처 등록 전에 터진다. 매처를 등록한 뒤
         * 타입만 맞춘 값을 돌려주는 표준 우회다(mockito-kotlin 과 같은 방식).
         */
        @Suppress("UNCHECKED_CAST")
        fun <T> cap(captor: ArgumentCaptor<T>): T {
            captor.capture()
            return null as T
        }
    }
}
