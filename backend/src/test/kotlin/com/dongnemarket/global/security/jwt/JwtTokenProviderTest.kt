package com.dongnemarket.global.security.jwt

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * Refresh Token 발급 고유성 계약을 고정한다.
 *
 * 배경 — jti 없이는 클레임이 sub/type/iat/exp(초 단위)뿐이라, 같은 회원이 같은 초에 발급받으면
 * 이전과 **바이트까지 동일한** 토큰이 생성된다. 그러면 회전(rotation)에서 저장소 교체가 A→A 가 되어
 * "구 토큰 재사용 거부"가 무력화된다(e2e specs/api/auth.spec.ts 가 실제로 이 문제로 깨졌다).
 *
 * 그래서 [JwtTokenProvider.createRefreshToken] 은 발급마다 UUID `jti` 를 넣는다. 이 테스트는
 * 같은 회원·같은 서명 키로 연속 발급해도(대부분 같은 초·같은 밀리초에 실행된다) 두 토큰이
 * 항상 다름을 고정한다 — iat/exp 가 우연히 갈라져도 jti 차이 단언은 영향을 받지 않는다.
 */
class JwtTokenProviderTest {
    // HS512 유지를 위해 64바이트 이상(운영 설정과 동일한 키 크기 조건)
    private val secret = "e2e-jwt-test-secret-key-must-be-at-least-64-bytes-long-0123456789abcdef"
    private val provider = JwtTokenProvider(secret, 900L, 604800L)

    private fun claims(token: String): Claims =
        Jwts
            .parser()
            .verifyWith(Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8)))
            .build()
            .parseSignedClaims(token)
            .payload

    @Test
    @DisplayName("같은 회원이 연속 발급해도 Refresh Token 은 jti 가 달라 항상 서로 다르다")
    fun `같은 회원이 연속 발급해도 Refresh Token 은 항상 서로 다르다`() {
        val first = provider.createRefreshToken(1L)
        val second = provider.createRefreshToken(1L)

        assertThat(second).isNotEqualTo(first)

        val firstClaims = claims(first)
        val secondClaims = claims(second)
        assertThat(firstClaims.id).isNotBlank()
        assertThat(secondClaims.id).isNotBlank()
        assertThat(secondClaims.id).isNotEqualTo(firstClaims.id)
    }

    @Test
    @DisplayName("jti 를 넣어도 기존 클레임 계약(sub/type)은 그대로다")
    fun `jti 를 넣어도 기존 클레임 계약은 그대로다`() {
        val token = provider.createRefreshToken(42L)
        val payload = claims(token)

        assertThat(payload.subject).isEqualTo("42")
        assertThat(payload.get("type", String::class.java)).isEqualTo("refresh")
        assertThat(provider.isRefreshToken(token)).isTrue()
        assertThat(provider.getMemberId(token)).isEqualTo(42L)
    }

    @Test
    @DisplayName("변경 범위 고정 — Access Token 에는 jti 를 넣지 않는다")
    fun `Access Token 에는 jti 를 넣지 않는다`() {
        val token = provider.createAccessToken(1L, "ROLE_USER")

        assertThat(claims(token).id).isNull()
    }
}
