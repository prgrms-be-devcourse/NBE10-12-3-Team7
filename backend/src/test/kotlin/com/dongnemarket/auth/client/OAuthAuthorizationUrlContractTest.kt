package com.dongnemarket.auth.client

import com.dongnemarket.auth.entity.OAuthProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.util.UriComponentsBuilder

/**
 * 인가 화면 URL 의 **제공자 계약**을 고정한다. `scope`·`response_type`·`code_challenge_method` 는
 * 카카오/구글과 맞춘 문자열이라 Kotlin 전환 과정에서 한 글자라도 달라지면 로그인이 깨진다.
 *
 * 값은 전부 명백한 dummy 다 — 실제 client id·secret 은 쓰지 않는다.
 * 쿼리 파라미터의 **이름과 값**을 검증하고, **순서는 계약으로 고정하지 않는다**
 * (원본이 순서를 보장한다는 근거가 없다).
 */
class OAuthAuthorizationUrlContractTest {
    private val factory =
        OAuthAuthorizationUrlFactory(
            kakaoAuthorizationUri = "https://kauth.example.test/oauth/authorize",
            kakaoClientId = "test-kakao-client-id",
            kakaoRedirectUri = "https://app.example.test/oauth/kakao/callback",
            googleAuthorizationUri = "https://accounts.example.test/o/oauth2/v2/auth",
            googleClientId = "test-google-client-id",
            googleRedirectUri = "https://app.example.test/oauth/google/callback",
        )

    private fun queryOf(url: String): Map<String, String?> =
        UriComponentsBuilder
            .fromUriString(url)
            .build()
            .queryParams
            .mapValues { (_, values) -> values.firstOrNull() }

    @Test
    fun `provider 별 redirect URI 를 그대로 돌려준다`() {
        assertThat(factory.redirectUri(OAuthProvider.KAKAO))
            .isEqualTo("https://app.example.test/oauth/kakao/callback")
        assertThat(factory.redirectUri(OAuthProvider.GOOGLE))
            .isEqualTo("https://app.example.test/oauth/google/callback")
    }

    @Nested
    @DisplayName("카카오 인가 URL")
    inner class Kakao {
        private val url =
            factory.build(OAuthProvider.KAKAO, "test-state", "test-code-challenge", null)

        @Test
        fun `authorization endpoint 를 그대로 쓴다`() {
            assertThat(url).startsWith("https://kauth.example.test/oauth/authorize?")
        }

        @Test
        fun `쿼리 파라미터 이름 집합이 전환 전과 같다`() {
            assertThat(queryOf(url).keys).containsExactlyInAnyOrder(
                "client_id",
                "redirect_uri",
                "response_type",
                "scope",
                "state",
                "code_challenge",
                "code_challenge_method",
            )
        }

        @Test
        fun `scope 와 PKCE 파라미터 값이 유지된다`() {
            val query = queryOf(url)
            assertThat(query["response_type"]).isEqualTo("code")
            assertThat(query["scope"]).isEqualTo("account_email")
            assertThat(query["code_challenge_method"]).isEqualTo("S256")
            assertThat(query["code_challenge"]).isEqualTo("test-code-challenge")
            assertThat(query["state"]).isEqualTo("test-state")
            assertThat(query["client_id"]).isEqualTo("test-kakao-client-id")
        }

        /** 카카오는 OIDC nonce 를 쓰지 않는다 — 파라미터 자체가 없어야 한다. */
        @Test
        fun `nonce 파라미터를 붙이지 않는다`() {
            assertThat(queryOf(url)).doesNotContainKey("nonce")
        }
    }

    @Nested
    @DisplayName("구글 인가 URL")
    inner class Google {
        private val url =
            factory.build(OAuthProvider.GOOGLE, "test-state", "test-code-challenge", "test-nonce")

        @Test
        fun `authorization endpoint 를 그대로 쓴다`() {
            assertThat(url).startsWith("https://accounts.example.test/o/oauth2/v2/auth?")
        }

        @Test
        fun `쿼리 파라미터 이름 집합이 전환 전과 같다`() {
            assertThat(queryOf(url).keys).containsExactlyInAnyOrder(
                "client_id",
                "redirect_uri",
                "response_type",
                "scope",
                "state",
                "nonce",
                "code_challenge",
                "code_challenge_method",
            )
        }

        @Test
        fun `openid email scope 와 nonce 가 유지된다`() {
            val query = queryOf(url)
            assertThat(query["response_type"]).isEqualTo("code")
            assertThat(query["scope"]).isEqualTo("openid email")
            assertThat(query["nonce"]).isEqualTo("test-nonce")
            assertThat(query["code_challenge_method"]).isEqualTo("S256")
            assertThat(query["client_id"]).isEqualTo("test-google-client-id")
        }
    }
}
