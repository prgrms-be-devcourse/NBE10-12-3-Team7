package com.dongnemarket.auth.client

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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * [GoogleIdTokenValidator]를 실제 RSA 서명 + MockWebServer로 서빙하는 JWKS로 검증한다.
 * 원격 구글에 의존하지 않고, 이 테스트가 직접 발급한(private key 보유) 토큰만 신뢰해야 하므로
 * 서명 검증이 통과하는 유일한 경로가 이 테스트의 키페어를 통해서인지가 핵심이다.
 */
class GoogleIdTokenValidatorTest {
    private lateinit var server: MockWebServer
    private lateinit var validator: GoogleIdTokenValidator
    private lateinit var privateKey: RSAPrivateKey

    @BeforeEach
    fun setUp() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        privateKey = keyPair.private as RSAPrivateKey
        val publicKey = keyPair.public as RSAPublicKey

        val jwk = RSAKey.Builder(publicKey).keyID(KEY_ID).build()
        val jwkSetJson = "{\"keys\":[" + jwk.toJSONString() + "]}"

        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jwkSetJson),
        )

        val jwkSetUri = server.url("/oauth2/v3/certs").toString()
        validator = GoogleIdTokenValidator(jwkSetUri, ISSUER, CLIENT_ID)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun sign(claims: JWTClaimsSet): String {
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(KEY_ID).build(),
                claims,
            )
        jwt.sign(RSASSASigner(privateKey))
        return jwt.serialize()
    }

    private fun validClaims(nonce: String): JWTClaimsSet.Builder {
        val now = Instant.now()
        return JWTClaimsSet
            .Builder()
            .issuer(ISSUER)
            .audience(CLIENT_ID)
            .subject("google-user-123")
            .claim("email", "user@example.com")
            .claim("email_verified", true)
            .claim("nonce", nonce)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
    }

    @Test
    @DisplayName("서명·iss·aud·exp·email_verified·nonce가 모두 유효하면 Jwt를 반환한다")
    fun validate_allValid_returnsJwt() {
        val nonce = UUID.randomUUID().toString()
        val token = sign(validClaims(nonce).build())

        val jwt = validator.validate(token, nonce)

        assertThat(jwt.subject).isEqualTo("google-user-123")
        assertThat(jwt.getClaimAsString("email")).isEqualTo("user@example.com")
    }

    @Test
    @DisplayName("nonce가 저장된 값과 다르면 거부한다")
    fun validate_wrongNonce_throws() {
        val token = sign(validClaims("expected-nonce").build())

        assertThatThrownBy { validator.validate(token, "different-nonce") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED)
    }

    @Test
    @DisplayName("만료된 토큰은 거부한다")
    fun validate_expiredToken_throws() {
        val nonce = "n1"
        val past = Instant.now().minusSeconds(600)
        val token =
            sign(
                validClaims(nonce)
                    .issueTime(Date.from(past.minusSeconds(300)))
                    .expirationTime(Date.from(past))
                    .build(),
            )

        assertThatThrownBy { validator.validate(token, nonce) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED)
    }

    @Test
    @DisplayName("issuer가 다르면 거부한다")
    fun validate_wrongIssuer_throws() {
        val nonce = "n2"
        val claims = validClaims(nonce).issuer("https://evil.example.com").build()
        val token = sign(claims)

        assertThatThrownBy { validator.validate(token, nonce) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED)
    }

    @Test
    @DisplayName("audience(client id)가 다르면 거부한다")
    fun validate_wrongAudience_throws() {
        val nonce = "n3"
        val claims = validClaims(nonce).audience("someone-elses-client-id").build()
        val token = sign(claims)

        assertThatThrownBy { validator.validate(token, nonce) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED)
    }

    @Test
    @DisplayName("email_verified가 false면 거부한다")
    fun validate_emailNotVerified_throws() {
        val nonce = "n4"
        val claims = validClaims(nonce).claim("email_verified", false).build()
        val token = sign(claims)

        assertThatThrownBy { validator.validate(token, nonce) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED)
    }

    @Test
    @DisplayName("다른 키로 서명된(위조된) 토큰은 서명 검증에서 거부한다")
    fun validate_wrongSigningKey_throws() {
        val nonce = "n5"
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val otherPrivateKey = generator.generateKeyPair().private as RSAPrivateKey

        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(KEY_ID).build(),
                validClaims(nonce).build(),
            )
        jwt.sign(RSASSASigner(otherPrivateKey))
        val token = jwt.serialize()

        assertThatThrownBy { validator.validate(token, nonce) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { e -> (e as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED)
    }

    companion object {
        private const val ISSUER = "https://accounts.google.com"
        private const val CLIENT_ID = "test-client-id.apps.googleusercontent.com"
        private const val KEY_ID = "test-key-1"
    }
}
