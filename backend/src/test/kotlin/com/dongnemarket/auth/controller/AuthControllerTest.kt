package com.dongnemarket.auth.controller

import com.dongnemarket.auth.entity.EmailVerification
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.member.repository.MemberAgreementRepository
import com.dongnemarket.member.repository.MemberRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * 실제 HTTP 요청으로 회원가입 성공/실패를 검증하는 통합 테스트 (H2, MySQL/Docker 불필요).
 * 응답 예시는 이 테스트로 직접 확인한 값이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var memberAgreementRepository: MemberAgreementRepository

    @Autowired
    lateinit var emailVerificationRepository: EmailVerificationRepository

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @AfterEach
    fun cleanUp() {
        memberAgreementRepository.deleteAll()
        memberRepository.deleteAll()
        emailVerificationRepository.deleteAll()
    }

    private fun extractRefreshTokenCookie(result: MvcResult): String? {
        val cookie = result.response.getCookie("refreshToken")
        return cookie?.value
    }

    /** 회원가입은 이메일 인증 완료를 전제로 하므로, signup을 호출하는 테스트는 먼저 인증 완료 상태를 만들어둔다. */
    private fun verifyEmail(email: String) {
        emailVerificationRepository.save(EmailVerification.verified(email, LocalDateTime.now()))
    }

    @Test
    @DisplayName("회원가입 성공 시 201과 회원 정보를 반환한다")
    fun signup_success() {
        verifyEmail("test@example.com")
        val body =
            """{ "email": "test@example.com", "password": "password123!", "nickname": "tester", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""

        mockMvc
            .perform(
                post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value(201))
            .andExpect(jsonPath("$.data.email").value("test@example.com"))
            .andExpect(jsonPath("$.data.nickname").value("tester"))
            .andExpect(jsonPath("$.data.memberId").exists())
    }

    @Test
    @DisplayName("이메일이 중복되면 409와 DUPLICATE_EMAIL을 반환한다")
    fun signup_duplicateEmail() {
        verifyEmail("dup@example.com")
        val first =
            """{ "email": "dup@example.com", "password": "password123!", "nickname": "first", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(first))

        val second =
            """{ "email": "dup@example.com", "password": "password123!", "nickname": "second", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(second))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("DUPLICATE_EMAIL"))
    }

    @Test
    @DisplayName("이메일 인증을 완료하지 않으면 400과 EMAIL_NOT_VERIFIED를 반환한다")
    fun signup_emailNotVerified() {
        val body =
            """{ "email": "unverified@example.com", "password": "password123!", "nickname": "unverifiedUser", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""

        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("EMAIL_NOT_VERIFIED"))
    }

    @Test
    @DisplayName("닉네임이 중복되면 409와 DUPLICATE_NICKNAME을 반환한다")
    fun signup_duplicateNickname() {
        verifyEmail("a@example.com")
        verifyEmail("b@example.com")
        val first =
            """{ "email": "a@example.com", "password": "password123!", "nickname": "dupNick", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(first))

        val second =
            """{ "email": "b@example.com", "password": "password123!", "nickname": "dupNick", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(second))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("DUPLICATE_NICKNAME"))
    }

    @Test
    @DisplayName("닉네임이 비어 있으면 400과 INVALID_INPUT_VALUE를 반환한다")
    fun signup_blankNickname() {
        val body = """{ "email": "valid@example.com", "password": "password123!", "nickname": "" }"""

        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    // ===== login =====

    @Test
    @DisplayName("올바른 이메일·비밀번호로 로그인하면 200과 accessToken을 반환하고, Refresh Token은 HttpOnly 쿠키로 내려간다")
    fun login_success() {
        verifyEmail("login@example.com")
        val signup =
            """{ "email": "login@example.com", "password": "password123!", "nickname": "loginUser", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated)

        val login = """{ "email": "login@example.com", "password": "password123!" }"""
        mockMvc
            .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.accessToken").exists())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andExpect(cookie().exists("refreshToken"))
            .andExpect(cookie().httpOnly("refreshToken", true))
            .andExpect(cookie().path("refreshToken", "/"))
    }

    @Test
    @DisplayName("autoLogin=true로 로그인하면 Refresh Token 쿠키에 Max-Age(7일)가 설정된 영속 쿠키로 내려간다")
    fun login_autoLoginTrue_setsPersistentCookieWithMaxAge() {
        verifyEmail("autologin-true@example.com")
        val signup =
            """{ "email": "autologin-true@example.com", "password": "password123!", "nickname": "autoLoginTrue", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated)

        val login = """{ "email": "autologin-true@example.com", "password": "password123!", "autoLogin": true }"""
        mockMvc
            .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
            .andExpect(status().isOk)
            .andExpect(cookie().exists("refreshToken"))
            .andExpect(cookie().maxAge("refreshToken", 604800))
    }

    @Test
    @DisplayName("autoLogin=false(또는 미지정)로 로그인하면 Refresh Token 쿠키가 Max-Age 없는 세션 쿠키로 내려간다")
    fun login_autoLoginFalse_setsSessionCookieWithoutMaxAge() {
        verifyEmail("autologin-false@example.com")
        val signup =
            """{ "email": "autologin-false@example.com", "password": "password123!", "nickname": "autoLoginFalse", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated)

        val login = """{ "email": "autologin-false@example.com", "password": "password123!", "autoLogin": false }"""
        mockMvc
            .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
            .andExpect(status().isOk)
            .andExpect(cookie().exists("refreshToken"))
            .andExpect(cookie().maxAge("refreshToken", -1))
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인하면 404와 MEMBER_NOT_FOUND를 반환한다")
    fun login_emailNotFound() {
        val body = """{ "email": "none@example.com", "password": "password123!" }"""

        mockMvc
            .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("MEMBER_NOT_FOUND"))
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401과 INVALID_PASSWORD를 반환한다")
    fun login_wrongPassword() {
        verifyEmail("pw@example.com")
        val signup =
            """{ "email": "pw@example.com", "password": "password123!", "nickname": "pwUser", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated)

        val login = """{ "email": "pw@example.com", "password": "wrongPassword" }"""
        mockMvc
            .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("INVALID_PASSWORD"))
    }

    @Test
    @DisplayName("이메일 형식이 올바르지 않으면 400과 INVALID_INPUT_VALUE를 반환한다")
    fun login_invalidEmailFormat() {
        val body = """{ "email": "not-an-email", "password": "password123!" }"""

        mockMvc
            .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    @DisplayName("비밀번호가 빈 값이면 400과 INVALID_INPUT_VALUE를 반환한다")
    fun login_blankPassword() {
        val body = """{ "email": "test@example.com", "password": "" }"""

        mockMvc
            .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    // ===== reissue =====

    @Test
    @DisplayName("유효한 Refresh Token 쿠키로 재발급하면 200과 새 accessToken을 반환한다")
    fun reissue_success() {
        verifyEmail("reissue@example.com")
        val signup =
            """{ "email": "reissue@example.com", "password": "password123!", "nickname": "reissueUser", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated)

        val login = """{ "email": "reissue@example.com", "password": "password123!" }"""
        val loginResult =
            mockMvc
                .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andReturn()
        val refreshToken = extractRefreshTokenCookie(loginResult)

        mockMvc
            .perform(post("/api/auth/reissue").cookie(Cookie("refreshToken", refreshToken)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accessToken").exists())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
    }

    @Test
    @DisplayName("Access Token을 쿠키에 담아 재발급을 시도하면 401과 INVALID_REFRESH_TOKEN을 반환한다")
    fun reissue_withAccessToken_returnsInvalidRefreshToken() {
        verifyEmail("reissue-access@example.com")
        val signup =
            """{ "email": "reissue-access@example.com", "password": "password123!", "nickname": "reissueAccess", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated)

        val login = """{ "email": "reissue-access@example.com", "password": "password123!" }"""
        val loginResult =
            mockMvc
                .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andReturn()
        val accessToken =
            objectMapper
                .readTree(loginResult.response.contentAsString)
                .path("data")
                .path("accessToken")
                .asText()

        mockMvc
            .perform(post("/api/auth/reissue").cookie(Cookie("refreshToken", accessToken)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    @DisplayName("Refresh Token 쿠키가 없으면 401과 INVALID_REFRESH_TOKEN을 반환한다")
    fun reissue_noCookie_returnsInvalidRefreshToken() {
        mockMvc
            .perform(post("/api/auth/reissue"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    @DisplayName("형식이 깨진(malformed) Refresh Token 쿠키로 재발급하면 401과 INVALID_REFRESH_TOKEN을 반환한다")
    fun reissue_malformedToken() {
        mockMvc
            .perform(post("/api/auth/reissue").cookie(Cookie("refreshToken", "not.a.valid.token")))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"))
    }

    // ===== logout =====

    @Test
    @DisplayName("로그인한 사용자가 로그아웃하면 200을 반환하고 Refresh Token 쿠키를 만료시킨다")
    fun logout_success() {
        verifyEmail("logout@example.com")
        val signup =
            """{ "email": "logout@example.com", "password": "password123!", "nickname": "logoutUser", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated)

        val login = """{ "email": "logout@example.com", "password": "password123!" }"""
        val loginResult =
            mockMvc
                .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andReturn()
        val accessToken =
            objectMapper
                .readTree(loginResult.response.contentAsString)
                .path("data")
                .path("accessToken")
                .asText()

        val logoutResult =
            mockMvc
                .perform(post("/api/auth/logout").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value(200))
                .andReturn()

        val clearedCookie = logoutResult.response.getCookie("refreshToken")
        assertThat(clearedCookie).isNotNull()
        assertThat(clearedCookie!!.maxAge).isEqualTo(0)
    }

    @Test
    @DisplayName("인증 헤더 없이 로그아웃을 시도하면 401을 반환한다")
    fun logout_withoutToken_returns401() {
        mockMvc
            .perform(post("/api/auth/logout"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("로그아웃을 여러 번 호출해도 항상 200을 반환한다(멱등)")
    fun logout_calledTwice_bothReturn200() {
        verifyEmail("logout-twice@example.com")
        val signup =
            """{ "email": "logout-twice@example.com", "password": "password123!", "nickname": "logoutTwice", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated)

        val login = """{ "email": "logout-twice@example.com", "password": "password123!" }"""
        val loginResult =
            mockMvc
                .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andReturn()
        val accessToken =
            objectMapper
                .readTree(loginResult.response.contentAsString)
                .path("data")
                .path("accessToken")
                .asText()

        mockMvc
            .perform(post("/api/auth/logout").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk)
        mockMvc
            .perform(post("/api/auth/logout").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("로그아웃 이후 기존 Refresh Token으로 재발급을 시도하면 401과 REFRESH_TOKEN_NOT_FOUND를 반환한다")
    fun logout_thenReissueWithOldRefreshToken_returns401RefreshTokenNotFound() {
        verifyEmail("logout-reissue@example.com")
        val signup =
            """{ "email": "logout-reissue@example.com", "password": "password123!", "nickname": "logoutReissue", "termsAgreed": true, "personalInfoCollectionAgreed": true }"""
        mockMvc
            .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated)

        val login = """{ "email": "logout-reissue@example.com", "password": "password123!" }"""
        val loginResult =
            mockMvc
                .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andReturn()
        val accessToken =
            objectMapper
                .readTree(loginResult.response.contentAsString)
                .path("data")
                .path("accessToken")
                .asText()
        val refreshToken = extractRefreshTokenCookie(loginResult)

        mockMvc
            .perform(post("/api/auth/logout").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk)

        mockMvc
            .perform(post("/api/auth/reissue").cookie(Cookie("refreshToken", refreshToken)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("REFRESH_TOKEN_NOT_FOUND"))
    }
}
