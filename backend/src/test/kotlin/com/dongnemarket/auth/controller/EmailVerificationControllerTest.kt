package com.dongnemarket.auth.controller

import com.dongnemarket.auth.mail.EmailSender
import com.dongnemarket.auth.repository.EmailVerificationCodeRepository
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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

/**
 * 실제 SMTP 발송 대신 [EmailSender]를 Mock으로 대체해 코드 생성/저장/쿨다운 로직만 검증한다.
 * (실제 메일 발송은 자격증명이 필요하므로 이 테스트 범위 밖)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailVerificationControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var emailVerificationRepository: EmailVerificationRepository

    @Autowired
    lateinit var emailVerificationCodeRepository: EmailVerificationCodeRepository

    @MockitoBean
    lateinit var emailSender: EmailSender

    @AfterEach
    fun cleanUp() {
        emailVerificationRepository.deleteAll()
        memberRepository.deleteAll()
    }

    @Test
    @DisplayName("가입되지 않은 이메일로 요청하면 201과 발송 예정 정보를 반환한다")
    fun requestVerification_success() {
        val body = """{ "email": "new@example.com" }"""

        mockMvc
            .perform(
                post("/api/auth/email-verifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value(201))
            .andExpect(jsonPath("$.data.email").value("new@example.com"))
            .andExpect(jsonPath("$.data.expiresAt").exists())

        verify(emailSender).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("이미 가입된 이메일로 요청하면 409와 DUPLICATE_EMAIL을 반환한다")
    fun requestVerification_duplicateEmail_returns409() {
        // 이 테스트는 "이미 가입된 회원"이라는 전제만 필요하므로, signup API(이메일 인증 선행 필요)를 거치지 않고 직접 저장한다.
        memberRepository.save(Member.createUser("taken@example.com", "encoded-password", "taken"))

        val body = """{ "email": "taken@example.com" }"""
        mockMvc
            .perform(
                post("/api/auth/email-verifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("DUPLICATE_EMAIL"))

        verify(emailSender, never()).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("60초 이내에 같은 이메일로 재요청하면 429와 EMAIL_VERIFICATION_REQUEST_TOO_SOON을 반환한다")
    fun requestVerification_withinCooldown_returns429() {
        val body = """{ "email": "cooldown@example.com" }"""

        mockMvc.perform(
            post("/api/auth/email-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

        mockMvc
            .perform(
                post("/api/auth/email-verifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error").value("EMAIL_VERIFICATION_REQUEST_TOO_SOON"))
    }

    @Test
    @DisplayName("이메일 형식이 올바르지 않으면 400과 INVALID_INPUT_VALUE를 반환한다")
    fun requestVerification_invalidEmailFormat_returns400() {
        val body = """{ "email": "not-an-email" }"""

        mockMvc
            .perform(
                post("/api/auth/email-verifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    // ===== confirm =====

    private fun requestVerificationAndGetCode(email: String): String {
        mockMvc.perform(
            post("/api/auth/email-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "email": "$email" }"""),
        )
        return emailVerificationCodeRepository.findCode(email).orElseThrow()
    }

    @Test
    @DisplayName("올바른 코드로 확인하면 200과 verified=true를 반환한다")
    fun confirmVerification_correctCode_success() {
        val code = requestVerificationAndGetCode("confirm@example.com")

        mockMvc
            .perform(
                post("/api/auth/email-verifications/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "confirm@example.com", "code": "$code" }"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.email").value("confirm@example.com"))
            .andExpect(jsonPath("$.data.verified").value(true))
    }

    @Test
    @DisplayName("코드가 일치하지 않으면 400과 INVALID_VERIFICATION_CODE를 반환한다")
    fun confirmVerification_wrongCode_returns400() {
        requestVerificationAndGetCode("confirm-wrong@example.com")

        mockMvc
            .perform(
                post("/api/auth/email-verifications/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "confirm-wrong@example.com", "code": "000000" }"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_VERIFICATION_CODE"))
    }

    @Test
    @DisplayName("코드 TTL이 만료되어 저장소에서 사라진 뒤 확인하면 404와 EMAIL_VERIFICATION_NOT_FOUND를 반환한다")
    fun confirmVerification_expiredCode_returns404() {
        requestVerificationAndGetCode("confirm-expired@example.com")
        // 실제 5분을 기다리는 대신, TTL 만료로 코드가 저장소에서 사라진 상태를 직접 흉내낸다.
        emailVerificationCodeRepository.delete("confirm-expired@example.com")

        mockMvc
            .perform(
                post("/api/auth/email-verifications/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "confirm-expired@example.com", "code": "123456" }"""),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("EMAIL_VERIFICATION_NOT_FOUND"))
    }

    @Test
    @DisplayName("인증 요청 이력이 없으면 404와 EMAIL_VERIFICATION_NOT_FOUND를 반환한다")
    fun confirmVerification_noRequestHistory_returns404() {
        mockMvc
            .perform(
                post("/api/auth/email-verifications/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "never-requested@example.com", "code": "123456" }"""),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("EMAIL_VERIFICATION_NOT_FOUND"))
    }

    @Test
    @DisplayName("이미 인증 완료된 건은 다른 코드를 보내도 200과 verified=true를 반환한다(멱등)")
    fun confirmVerification_alreadyVerified_returnsSuccessIdempotently() {
        val code = requestVerificationAndGetCode("confirm-twice@example.com")
        mockMvc.perform(
            post("/api/auth/email-verifications/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "email": "confirm-twice@example.com", "code": "$code" }"""),
        )

        mockMvc
            .perform(
                post("/api/auth/email-verifications/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "confirm-twice@example.com", "code": "wrong-code" }"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.verified").value(true))
    }

    @Test
    @DisplayName("확인 요청 이메일 형식이 올바르지 않으면 400과 INVALID_INPUT_VALUE를 반환한다")
    fun confirmVerification_invalidEmailFormat_returns400() {
        mockMvc
            .perform(
                post("/api/auth/email-verifications/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "not-an-email", "code": "123456" }"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }
}
