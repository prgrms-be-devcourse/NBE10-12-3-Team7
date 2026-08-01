package com.dongnemarket.auth.client

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * 구글 ID Token(JWS)을 로컬에서 검증한다. JWKS 공개키로 서명을 확인하므로 매 로그인마다 구글에
 * 원격 검증(tokeninfo)을 호출하지 않는다(JWKS 는 [NimbusJwtDecoder] 가 캐싱한다).
 *
 * 검증 항목: RS256 서명, exp, issuer, audience(client id), `email_verified == true`.
 * nonce 는 별도로(Redis state record 와) 상수시간 비교한다 — 검증 실패 시 어떤 조건이 틀렸는지 구분해
 * 노출하지 않고 [ErrorCode.OAUTH_AUTHORIZATION_FAILED] 로 통일한다.
 *
 * 전환 규칙 — 검증기 3종의 **구성 순서**(timestamp+issuer → audience → email_verified)와
 * 실패 시 단일 ErrorCode 로 통일하는 처리를 그대로 유지한다. nonce 비교는 계속 [MessageDigest.isEqual]
 * 상수시간 비교를 쓴다(`==` 로 바꾸면 타이밍 공격 방어가 사라진다).
 */
@Component
class GoogleIdTokenValidator(
    @param:Value("\${oauth.google.jwk-set-uri}") jwkSetUri: String,
    @param:Value("\${oauth.google.issuer}") issuer: String,
    @param:Value("\${oauth.google.client-id}") clientId: String,
) {
    private val jwtDecoder: JwtDecoder

    init {
        val decoder =
            NimbusJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build()

        val withTimestampAndIssuer: OAuth2TokenValidator<Jwt> = JwtValidators.createDefaultWithIssuer(issuer)
        val audienceValidator: OAuth2TokenValidator<Jwt> =
            JwtClaimValidator<List<String>?>(JwtClaimNames.AUD) { aud -> aud != null && aud.contains(clientId) }
        val emailVerifiedValidator: OAuth2TokenValidator<Jwt> =
            JwtClaimValidator<Boolean?>("email_verified") { value -> java.lang.Boolean.TRUE == value }

        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(withTimestampAndIssuer, audienceValidator, emailVerifiedValidator),
        )
        this.jwtDecoder = decoder
    }

    /** @param expectedNonce Redis state record 에 저장된 oidcNonce(평문) */
    fun validate(
        idToken: String?,
        expectedNonce: String?,
    ): Jwt {
        val jwt: Jwt =
            try {
                jwtDecoder.decode(idToken)
            } catch (e: JwtException) {
                throw BusinessException(ErrorCode.OAUTH_AUTHORIZATION_FAILED)
            }

        val nonceClaim = jwt.getClaimAsString("nonce")
        if (!constantTimeEquals(expectedNonce, nonceClaim)) {
            throw BusinessException(ErrorCode.OAUTH_AUTHORIZATION_FAILED)
        }
        return jwt
    }

    private fun constantTimeEquals(
        expected: String?,
        actual: String?,
    ): Boolean {
        if (expected == null || actual == null) {
            return false
        }
        return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
    }
}
