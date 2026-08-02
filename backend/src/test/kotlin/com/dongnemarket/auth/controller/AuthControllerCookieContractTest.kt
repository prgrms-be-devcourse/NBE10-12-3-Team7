package com.dongnemarket.auth.controller

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * OAuth 브라우저 귀속(`oauth_bcid`) 쿠키와 reissue 의 쿠키 입력 계약을 실제 HTTP 왕복으로 고정한다.
 *
 * 기존 `AuthControllerTest`(Java)는 refresh 쿠키의 영속/세션 구분과 만료를 다루지만, **OAuth 4개
 * endpoint 의 쿠키 계약은 어디에도 없었다** — 7단계에서 처음 고정한다.
 *
 * 참고 — 각 요청은 `X-Forwarded-For` 를 서로 다르게 줘 전역 rate limit 버킷을 분리한다(테스트 격리 목적).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerCookieContractTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private var xffSeq = 0

    private fun freshXff(): String = "203.0.113.${++xffSeq}"

    private fun bcidSetCookies(headers: List<String>): List<String> = headers.filter { it.startsWith("oauth_bcid=") }

    @Nested
    @DisplayName("OAuth authorization — BCID 발급·재사용")
    inner class Authorization {
        @Test
        fun `쿠키가 없으면 32바이트 URL-safe BCID 를 새로 발급한다`() {
            val result =
                mockMvc
                    .perform(post("/api/auth/oauth/kakao/authorization").header("X-Forwarded-For", freshXff()))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.authorizationUrl").exists())
                    .andExpect(jsonPath("$.data.state").exists())
                    .andReturn()

            val cookies = bcidSetCookies(result.response.getHeaders("Set-Cookie"))
            assertThat(cookies).hasSize(1)
            val header = cookies[0]
            val value = header.substringAfter("oauth_bcid=").substringBefore(";")
            assertThat(value).hasSize(43)
            assertThat(value).doesNotContain("=")
            assertThat(value).matches("[A-Za-z0-9_-]+")
            assertThat(header).contains("Path=/api/auth/oauth")
            assertThat(header).contains("HttpOnly")
            assertThat(header).contains("SameSite=Lax")
            assertThat(header).contains("Max-Age=")
            assertThat(header).doesNotContain("Domain=")
        }

        @Test
        fun `기존 쿠키가 있으면 같은 값을 재사용하고 Max-Age 갱신을 위해 다시 내려보낸다`() {
            val result =
                mockMvc
                    .perform(
                        post("/api/auth/oauth/kakao/authorization")
                            .header("X-Forwarded-For", freshXff())
                            .cookie(Cookie("oauth_bcid", "test-existing-bcid-value")),
                    ).andExpect(status().isOk)
                    .andReturn()

            val cookies = bcidSetCookies(result.response.getHeaders("Set-Cookie"))
            assertThat(cookies).hasSize(1)
            assertThat(cookies[0]).startsWith("oauth_bcid=test-existing-bcid-value;")
            assertThat(cookies[0]).contains("Max-Age=")
        }

        @Test
        fun `blank 쿠키는 없는 것으로 보고 새 값을 발급한다`() {
            val result =
                mockMvc
                    .perform(
                        post("/api/auth/oauth/google/authorization")
                            .header("X-Forwarded-For", freshXff())
                            .cookie(Cookie("oauth_bcid", "")),
                    ).andExpect(status().isOk)
                    .andReturn()

            val cookies = bcidSetCookies(result.response.getHeaders("Set-Cookie"))
            assertThat(cookies).hasSize(1)
            val value = cookies[0].substringAfter("oauth_bcid=").substringBefore(";")
            assertThat(value).hasSize(43)
        }

        @Test
        fun `구글 시작도 같은 BCID 쿠키 계약이다`() {
            val result =
                mockMvc
                    .perform(post("/api/auth/oauth/google/authorization").header("X-Forwarded-For", freshXff()))
                    .andExpect(status().isOk)
                    .andReturn()

            assertThat(bcidSetCookies(result.response.getHeaders("Set-Cookie"))).hasSize(1)
        }
    }

    @Nested
    @DisplayName("OAuth login — BCID 가 없으면 만들지 않고 거부한다")
    inner class OAuthLoginWithoutBcid {
        private fun body(): String = """{"code":"test-authorization-code","state":"test-state"}"""

        @Test
        fun `카카오 로그인 완료에 BCID 쿠키가 없으면 INVALID_OAUTH_STATE 이고 새 쿠키를 만들지 않는다`() {
            val result =
                mockMvc
                    .perform(
                        post("/api/auth/oauth/kakao/login")
                            .header("X-Forwarded-For", freshXff())
                            .contentType("application/json")
                            .content(body()),
                    ).andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.error").value("INVALID_OAUTH_STATE"))
                    .andReturn()

            assertThat(bcidSetCookies(result.response.getHeaders("Set-Cookie"))).isEmpty()
        }

        @Test
        fun `구글 로그인 완료도 동일하다`() {
            val result =
                mockMvc
                    .perform(
                        post("/api/auth/oauth/google/login")
                            .header("X-Forwarded-For", freshXff())
                            .contentType("application/json")
                            .content(body()),
                    ).andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.error").value("INVALID_OAUTH_STATE"))
                    .andReturn()

            assertThat(bcidSetCookies(result.response.getHeaders("Set-Cookie"))).isEmpty()
        }

        /** BCID 는 있지만 state 가 발급된 적 없으면 — state 소비 실패로 같은 오류. 제공자 네트워크 호출까지 가지 않는다. */
        @Test
        fun `BCID 는 있어도 모르는 state 면 INVALID_OAUTH_STATE 다`() {
            mockMvc
                .perform(
                    post("/api/auth/oauth/kakao/login")
                        .header("X-Forwarded-For", freshXff())
                        .cookie(Cookie("oauth_bcid", "test-existing-bcid-value"))
                        .contentType("application/json")
                        .content(body()),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error").value("INVALID_OAUTH_STATE"))
        }
    }

    @Nested
    @DisplayName("reissue — 쿠키 입력 검증")
    inner class ReissueCookie {
        /** 기존 테스트는 쿠키 미존재만 다뤘다 — blank 값도 service 호출 전에 거부돼야 한다. */
        @Test
        fun `blank refreshToken 쿠키는 INVALID_REFRESH_TOKEN 으로 거부된다`() {
            mockMvc
                .perform(
                    post("/api/auth/reissue")
                        .header("X-Forwarded-For", freshXff())
                        .cookie(Cookie("refreshToken", "")),
                ).andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"))
        }
    }
}
