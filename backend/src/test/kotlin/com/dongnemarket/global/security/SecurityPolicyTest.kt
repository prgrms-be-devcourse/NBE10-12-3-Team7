package com.dongnemarket.global.security

import com.dongnemarket.global.security.jwt.JwtTokenProvider
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets

/**
 * 공통 보안 정책 스모크 테스트.
 * 인증 없는 보호 API 가 공통 ErrorResponse 포맷의 401 을 주는지,
 * 공개 경로(Swagger)가 인증 없이 열려 있는지 검증한다. (H2, MySQL 불필요)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityPolicyTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    fun `인증 없이 보호 API 접근 시 401 + 공통 ErrorResponse(JSON)`() {
        mockMvc
            .perform(get("/api/admin/members"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.timestamp").exists())
    }

    @Test
    fun `Swagger api-docs 는 인증 없이 접근 가능(200)`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
    }

    @Test
    fun `깨진(malformed) 토큰으로 접근 시 401 + INVALID_TOKEN`() {
        mockMvc
            .perform(get("/api/admin/members").header("Authorization", "Bearer not.a.valid.token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.error").value("INVALID_TOKEN"))
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `다른 키로 서명된(위조) 토큰으로 접근 시 401 + INVALID_TOKEN`() {
        val wrongKey =
            Keys.hmacShaKeyFor(
                "a-totally-different-secret-key-for-forgery-0123456789".toByteArray(StandardCharsets.UTF_8),
            )
        val forgedToken =
            Jwts
                .builder()
                .subject("1")
                .claim("role", "ROLE_USER")
                .signWith(wrongKey)
                .compact()

        mockMvc
            .perform(get("/api/admin/members").header("Authorization", "Bearer $forgedToken"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_TOKEN"))
    }

    @Test
    fun `Refresh Token으로 보호 API 접근 시 401 + INVALID_TOKEN (Access Token 용도로 사용 불가)`() {
        val refreshToken = jwtTokenProvider.createRefreshToken(1L)

        mockMvc
            .perform(get("/api/admin/members").header("Authorization", "Bearer $refreshToken"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_TOKEN"))
    }
}
