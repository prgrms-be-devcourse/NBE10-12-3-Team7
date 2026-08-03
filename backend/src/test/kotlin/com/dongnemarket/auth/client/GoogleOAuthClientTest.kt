package com.dongnemarket.auth.client

import com.dongnemarket.auth.config.OAuthWebClientConfig
import com.dongnemarket.auth.entity.OAuthProvider
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * [GoogleOAuthClient]를 MockWebServer(토큰 교환 + JWKS)로 검증한다. 두 호출 모두 같은
 * 서버 하나에서 순서대로(토큰 → JWKS) enqueue한다 — 클라이언트 호출 순서와 일치한다.
 */
class GoogleOAuthClientTest {
    private lateinit var server: MockWebServer
    private lateinit var privateKey: RSAPrivateKey
    private lateinit var jwkSetJson: String

    @BeforeEach
    fun setUp() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        privateKey = keyPair.private as RSAPrivateKey
        val jwk = RSAKey.Builder(keyPair.public as RSAPublicKey).keyID(KEY_ID).build()
        jwkSetJson = "{\"keys\":[" + jwk.toJSONString() + "]}"

        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun signIdToken(nonce: String): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .subject("google-user-1")
                .claim("email", "user@gmail.com")
                .claim("email_verified", true)
                .claim("nonce", nonce)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build()
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(KEY_ID).build(),
                claims,
            )
        jwt.sign(RSASSASigner(privateKey))
        return jwt.serialize()
    }

    private fun client(
        responseTimeoutMillis: Long,
        maxInMemorySizeBytes: Int,
    ): GoogleOAuthClient {
        val webClient =
            OAuthWebClientConfig()
                .oauthWebClient(WebClient.builder(), 2000, responseTimeoutMillis, maxInMemorySizeBytes)
        val validator =
            GoogleIdTokenValidator(
                server.url("/oauth2/v3/certs").toString(),
                ISSUER,
                CLIENT_ID,
            )
        return GoogleOAuthClient(
            webClient,
            validator,
            CLIENT_ID,
            "test-client-secret",
            server.url("/token").toString(),
            responseTimeoutMillis,
        )
    }

    @Test
    @DisplayName("성공: 토큰 교환 응답의 id_token을 검증해 신원을 반환한다")
    fun resolveIdentity_success() {
        val nonce = "nonce-1"
        val idToken = signIdToken(nonce)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"a\",\"id_token\":\"" + idToken + "\"}"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jwkSetJson),
        )

        val identity =
            client(5000, 1_000_000)
                .resolveIdentity("code", "verifier", "https://app.example.com/callback", nonce)

        assertThat(identity.provider).isEqualTo(OAuthProvider.GOOGLE)
        assertThat(identity.providerUserId).isEqualTo("google-user-1")
        assertThat(identity.email).isEqualTo("user@gmail.com")
    }

    @Test
    @DisplayName("토큰 교환에서 4xx가 오면 잘못되거나 만료된 인가 코드로 매핑한다")
    fun resolveIdentity_tokenExchange4xx_mapsToAuthorizationFailed() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_grant\"}"),
        )

        assertThatThrownBy {
            client(5000, 1_000_000)
                .resolveIdentity("bad-code", "verifier", "https://app.example.com/callback", "nonce")
        }.isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED)
    }

    @Test
    @DisplayName("토큰 교환에서 5xx가 오면 제공자 장애로 매핑한다")
    fun resolveIdentity_tokenExchange5xx_mapsToProviderError() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))

        assertThatThrownBy {
            client(5000, 1_000_000)
                .resolveIdentity("code", "verifier", "https://app.example.com/callback", "nonce")
        }.isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR)
    }

    @Test
    @DisplayName("응답이 설정된 타임아웃보다 늦으면 제공자 장애로 매핑한다")
    fun resolveIdentity_timeout_mapsToProviderError() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id_token\":\"x\"}")
                .setBodyDelay(3, TimeUnit.SECONDS),
        )

        assertThatThrownBy {
            client(500, 1_000_000)
                .resolveIdentity("code", "verifier", "https://app.example.com/callback", "nonce")
        }.isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR)
    }

    @Test
    @DisplayName("연결이 끊기면 제공자 장애로 매핑한다")
    fun resolveIdentity_connectionReset_mapsToProviderError() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThatThrownBy {
            client(2000, 1_000_000)
                .resolveIdentity("code", "verifier", "https://app.example.com/callback", "nonce")
        }.isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR)
    }

    @Test
    @DisplayName("응답 본문이 깨진 JSON이면 제공자 장애로 매핑한다")
    fun resolveIdentity_malformedJson_mapsToProviderError() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{broken"),
        )

        assertThatThrownBy {
            client(5000, 1_000_000)
                .resolveIdentity("code", "verifier", "https://app.example.com/callback", "nonce")
        }.isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR)
    }

    @Test
    @DisplayName("응답이 설정된 최대 크기를 넘으면 제공자 장애로 매핑한다")
    fun resolveIdentity_oversizedResponse_mapsToProviderError() {
        val hugeBody = "{\"id_token\":\"" + "a".repeat(5000) + "\"}"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(hugeBody),
        )

        assertThatThrownBy {
            client(5000, 256)
                .resolveIdentity("code", "verifier", "https://app.example.com/callback", "nonce")
        }.isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR)
    }

    @Test
    @DisplayName("id_token이 응답에 없으면 제공자 장애로 매핑한다")
    fun resolveIdentity_missingIdToken_mapsToProviderError() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"a\"}"),
        )

        assertThatThrownBy {
            client(5000, 1_000_000)
                .resolveIdentity("code", "verifier", "https://app.example.com/callback", "nonce")
        }.isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR)
    }

    companion object {
        private const val ISSUER = "https://accounts.google.com"
        private const val CLIENT_ID = "test-client-id.apps.googleusercontent.com"
        private const val KEY_ID = "google-test-key"
    }
}
