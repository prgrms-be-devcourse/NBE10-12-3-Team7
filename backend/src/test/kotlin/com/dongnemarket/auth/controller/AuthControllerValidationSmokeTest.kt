package com.dongnemarket.auth.controller

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
 * mock 없이 실제 context 로 endpoint 12개가 **전부 mapping 되어 요청을 받는지**(404/405 아님)와,
 * validation·오류가 기존 전역 `ErrorResponse` 형식으로 나가는지를 smoke 로 고정한다.
 *
 * 각 요청은 `X-Forwarded-For` 를 서로 다르게 줘 전역 rate limit 버킷을 분리한다(테스트 격리 목적).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerValidationSmokeTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private var xffSeq = 0

    private fun xff(): String = "192.0.2.${++xffSeq}"

    @Nested
    @DisplayName("endpoint 12개 smoke — 전부 mapping 되어 있고 404/405 가 아니다")
    inner class TwelveEndpoints {
        @Test
        fun `AuthController 8개가 요청을 받는다`() {
            // 본문 검증 실패(400)·인증 실패(401)·컨트롤러 검증(401/400)은 모두 "mapping 존재" 증거다.
            mockMvc
                .perform(post("/api/auth/signup").header("X-Forwarded-For", xff()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest)
            mockMvc
                .perform(post("/api/auth/login").header("X-Forwarded-For", xff()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest)
            mockMvc
                .perform(post("/api/auth/reissue").header("X-Forwarded-For", xff()))
                .andExpect(status().isUnauthorized)
            mockMvc
                .perform(post("/api/auth/logout").header("X-Forwarded-For", xff()))
                .andExpect(status().isUnauthorized)
            mockMvc
                .perform(post("/api/auth/oauth/kakao/authorization").header("X-Forwarded-For", xff()))
                .andExpect(status().isOk)
            mockMvc
                .perform(post("/api/auth/oauth/google/authorization").header("X-Forwarded-For", xff()))
                .andExpect(status().isOk)
            mockMvc
                .perform(
                    post("/api/auth/oauth/kakao/login")
                        .header("X-Forwarded-For", xff())
                        .contentType("application/json")
                        .content("""{"code":"test-authorization-code","state":"test-state"}"""),
                ).andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    post("/api/auth/oauth/google/login")
                        .header("X-Forwarded-For", xff())
                        .contentType("application/json")
                        .content("""{"code":"test-authorization-code","state":"test-state"}"""),
                ).andExpect(status().isBadRequest)
        }

        @Test
        fun `이메일·비밀번호 controller 4개가 요청을 받는다`() {
            for (path in listOf(
                "/api/auth/email-verifications",
                "/api/auth/email-verifications/confirm",
                "/api/auth/password-resets",
                "/api/auth/password-resets/confirm",
            )) {
                mockMvc
                    .perform(post(path).header("X-Forwarded-For", xff()).contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest)
            }
        }
    }

    @Nested
    @DisplayName("오류 응답 형식")
    inner class ErrorShape {
        @Test
        fun `validation 실패는 기존 ErrorResponse 형식(status·error·message)으로 나간다`() {
            mockMvc
                .perform(
                    post("/api/auth/login")
                        .header("X-Forwarded-For", xff())
                        .contentType("application/json")
                        .content("""{"email":"not-an-email","password":""}"""),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists())
        }

        /** 원본(Java `6515742`) 실측 계약 — 전역 핸들러가 `HttpMessageNotReadable` 을 따로 다루지 않아 500 이다. */
        @Test
        fun `malformed JSON 은 원본과 같은 500 으로 처리된다`() {
            mockMvc
                .perform(
                    post("/api/auth/signup")
                        .header("X-Forwarded-For", xff())
                        .contentType("application/json")
                        .content("{not-json"),
                ).andExpect(status().isInternalServerError)
        }
    }
}
