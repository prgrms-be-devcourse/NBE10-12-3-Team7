package com.dongnemarket.auth.controller

import com.dongnemarket.auth.client.GoogleOAuthClient
import com.dongnemarket.auth.client.KakaoOAuthClient
import com.dongnemarket.auth.client.OAuthClient
import com.dongnemarket.auth.dto.LoginResponse
import com.dongnemarket.auth.dto.OAuthAuthorizationStart
import com.dongnemarket.auth.dto.SignupResponse
import com.dongnemarket.auth.dto.TokenResponse
import com.dongnemarket.auth.service.AuthService
import com.dongnemarket.member.entity.Member
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.HexFormat

/**
 * controller → service **위임 계약**을 고정한다: 무엇을 뽑아서(XFF 첫 값 trim, User-Agent, BCID hash,
 * cookie 값), 어떤 client 를 고정해서(카카오/구글 endpoint 별), 어떤 순서로(validation → service) 넘기는가.
 *
 * [AuthService] 만 mock 으로 바꿔 controller 계층의 입출력만 격리 검증한다 — 응답 메시지·상태코드·
 * 토큰 노출 위치(access 는 body, refresh 는 쿠키만)도 함께 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerOrchestrationContractTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var authService: AuthService

    @Autowired
    lateinit var kakaoOAuthClient: KakaoOAuthClient

    @Autowired
    lateinit var googleOAuthClient: GoogleOAuthClient

    /** controller 의 `sha256Hex` 와 같은 의미(SHA-256 + 플랫폼 기본 charset + hex). */
    private fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charset.defaultCharset())),
        )

    private val signupJson =
        """
        {"email":"test@example.com","password":"Password123!","nickname":"tester",
         "termsAgreed":true,"personalInfoCollectionAgreed":true}
        """.trimIndent()

    @Nested
    @DisplayName("signup — IP·User-Agent 추출")
    inner class Signup {
        @BeforeEach
        fun stub() {
            val member = mock(Member::class.java)
            `when`(member.email).thenReturn("test@example.com")
            `when`(member.nickname).thenReturn("tester")
            // thenReturn 인자 안에서 mock(member)을 호출하면 UnfinishedStubbing 이 되므로 미리 만든다.
            val response = SignupResponse.from(member)
            `when`(authService.signup(any(), anyString(), anyString())).thenReturn(response)
        }

        @Test
        fun `X-Forwarded-For 첫 값을 trim 해 넘기고 201 과 메시지를 반환한다`() {
            mockMvc
                .perform(
                    post("/api/auth/signup")
                        .header("X-Forwarded-For", " 198.51.100.1 , 10.0.0.2")
                        .header("User-Agent", "test-user-agent")
                        .contentType("application/json")
                        .content(signupJson),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))

            verify(authService).signup(any(), eq("198.51.100.1"), eq("test-user-agent"))
        }

        @Test
        fun `X-Forwarded-For 가 없으면 remoteAddr 를 넘긴다`() {
            mockMvc
                .perform(
                    post("/api/auth/signup")
                        .header("User-Agent", "test-user-agent")
                        .contentType("application/json")
                        .content(signupJson)
                        .with { request ->
                            request.remoteAddr = "198.51.100.77"
                            request
                        },
                ).andExpect(status().isCreated)

            verify(authService).signup(any(), eq("198.51.100.77"), eq("test-user-agent"))
        }
    }

    @Nested
    @DisplayName("login·reissue — 토큰 노출 위치와 rotation")
    inner class LoginAndReissue {
        @Test
        fun `login 은 accessToken 만 body 에 반환하고 refreshToken 은 쿠키로만 내려간다`() {
            `when`(authService.login(any())).thenReturn(LoginResponse.of("test-access-token", "test-refresh-token"))

            val result =
                mockMvc
                    .perform(
                        post("/api/auth/login")
                            .contentType("application/json")
                            .content("""{"email":"test@example.com","password":"Password123!","autoLogin":true}"""),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.message").value("로그인이 완료되었습니다."))
                    .andExpect(jsonPath("$.data.accessToken").value("test-access-token"))
                    .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                    .andReturn()

            val cookie = result.response.getHeaders("Set-Cookie").single { it.startsWith("refreshToken=") }
            assertThat(cookie).startsWith("refreshToken=test-refresh-token;")
            assertThat(cookie).contains("Max-Age=", "Path=/", "HttpOnly", "SameSite=Lax")
            assertThat(cookie).doesNotContain("Domain=")
        }

        @Test
        fun `autoLogin=false 면 Max-Age 없는 세션 쿠키다`() {
            `when`(authService.login(any())).thenReturn(LoginResponse.of("test-access-token", "test-refresh-token"))

            val result =
                mockMvc
                    .perform(
                        post("/api/auth/login")
                            .contentType("application/json")
                            .content("""{"email":"test@example.com","password":"Password123!","autoLogin":false}"""),
                    ).andExpect(status().isOk)
                    .andReturn()

            val cookie = result.response.getHeaders("Set-Cookie").single { it.startsWith("refreshToken=") }
            assertThat(cookie).doesNotContain("Max-Age=", "Expires=")
        }

        @Test
        fun `reissue 는 쿠키 값을 그대로 service 에 넘기고 회전된 토큰을 새 쿠키로 내려보낸다`() {
            `when`(authService.reissue("test-refresh-token"))
                .thenReturn(TokenResponse.of("test-new-access-token", "test-rotated-refresh-token"))

            val result =
                mockMvc
                    .perform(post("/api/auth/reissue").cookie(Cookie("refreshToken", "test-refresh-token")))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.message").value("토큰이 재발급되었습니다."))
                    .andExpect(jsonPath("$.data.accessToken").value("test-new-access-token"))
                    .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                    .andReturn()

            verify(authService).reissue("test-refresh-token")
            val cookie = result.response.getHeaders("Set-Cookie").single { it.startsWith("refreshToken=") }
            assertThat(cookie).startsWith("refreshToken=test-rotated-refresh-token;")
            assertThat(cookie).contains("Max-Age=")
        }
    }

    @Nested
    @DisplayName("OAuth — provider 별 client 고정과 BCID hash 전달")
    inner class OAuth {
        @BeforeEach
        fun stub() {
            `when`(authService.startAuthorization(any(), anyString()))
                .thenReturn(OAuthAuthorizationStart("https://example.test/authorize", "test-state", 300L))
            `when`(authService.oauthLogin(any(), anyString(), anyString(), anyString()))
                .thenReturn(LoginResponse.of("test-access-token", "test-refresh-token"))
        }

        @Test
        fun `카카오 시작은 KakaoOAuthClient bean 과 BCID 의 SHA-256 hex 를 넘긴다`() {
            mockMvc
                .perform(
                    post("/api/auth/oauth/kakao/authorization")
                        .cookie(Cookie("oauth_bcid", "test-bcid-value")),
                ).andExpect(status().isOk)

            val clientCaptor = ArgumentCaptor.forClass(OAuthClient::class.java)
            verify(authService).startAuthorization(clientCaptor.capture(), eq(sha256Hex("test-bcid-value")))
            assertThat(clientCaptor.value).isSameAs(kakaoOAuthClient)
        }

        @Test
        fun `구글 시작은 GoogleOAuthClient bean 을 넘긴다`() {
            mockMvc
                .perform(
                    post("/api/auth/oauth/google/authorization")
                        .cookie(Cookie("oauth_bcid", "test-bcid-value")),
                ).andExpect(status().isOk)

            val clientCaptor = ArgumentCaptor.forClass(OAuthClient::class.java)
            verify(authService).startAuthorization(clientCaptor.capture(), anyString())
            assertThat(clientCaptor.value).isSameAs(googleOAuthClient)
        }

        @Test
        fun `카카오 로그인 완료는 code·state 원문과 BCID hash 를 그대로 넘긴다`() {
            mockMvc
                .perform(
                    post("/api/auth/oauth/kakao/login")
                        .cookie(Cookie("oauth_bcid", "test-bcid-value"))
                        .contentType("application/json")
                        .content("""{"code":"test-authorization-code","state":"test-state"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.message").value("로그인이 완료되었습니다."))
                .andExpect(jsonPath("$.data.accessToken").value("test-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())

            val clientCaptor = ArgumentCaptor.forClass(OAuthClient::class.java)
            verify(authService).oauthLogin(
                clientCaptor.capture(),
                eq("test-authorization-code"),
                eq("test-state"),
                eq(sha256Hex("test-bcid-value")),
            )
            assertThat(clientCaptor.value).isSameAs(kakaoOAuthClient)
        }

        @Test
        fun `구글 로그인 완료는 GoogleOAuthClient bean 으로 고정된다`() {
            mockMvc
                .perform(
                    post("/api/auth/oauth/google/login")
                        .cookie(Cookie("oauth_bcid", "test-bcid-value"))
                        .contentType("application/json")
                        .content("""{"code":"test-authorization-code","state":"test-state"}"""),
                ).andExpect(status().isOk)

            val clientCaptor = ArgumentCaptor.forClass(OAuthClient::class.java)
            verify(authService).oauthLogin(clientCaptor.capture(), anyString(), anyString(), anyString())
            assertThat(clientCaptor.value).isSameAs(googleOAuthClient)
        }
    }

    @Nested
    @DisplayName("validation·binding 이 service 호출보다 먼저다")
    inner class ValidationBeforeService {
        /**
         * 원본(Java `6515742`) 실측: body 누락은 `HttpMessageNotReadableException` 이고 전역 핸들러가
         * 이를 따로 다루지 않아 **500** 이다. `@RequestBody` 파라미터를 Kotlin nullable 로 두면 Spring 이
         * body 를 선택 사항으로 해석해 null 이 service 까지 흘러가는 다른 동작이 된다 — non-null 유지가
         * 이 원본 계약을 보존한다(개선하지 않는다).
         */
        @Test
        fun `body 가 없으면 원본과 같은 500 이고 service 는 호출되지 않는다`() {
            mockMvc
                .perform(post("/api/auth/signup").contentType("application/json"))
                .andExpect(status().isInternalServerError)

            verifyNoInteractions(authService)
        }

        @Test
        fun `malformed JSON 도 원본과 같은 500 이고 service 는 호출되지 않는다`() {
            mockMvc
                .perform(post("/api/auth/login").contentType("application/json").content("{not-json"))
                .andExpect(status().isInternalServerError)

            verifyNoInteractions(authService)
        }

        @Test
        fun `필드 validation 실패는 400 INVALID_INPUT_VALUE 이고 service 는 호출되지 않는다`() {
            mockMvc
                .perform(
                    post("/api/auth/login")
                        .contentType("application/json")
                        .content("""{"email":"","password":""}"""),
                ).andExpect(status().isBadRequest)

            verifyNoInteractions(authService)
        }
    }
}
