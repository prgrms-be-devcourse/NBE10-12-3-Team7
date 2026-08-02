package com.dongnemarket.auth.client

import com.dongnemarket.auth.entity.OAuthProvider
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.function.Supplier

/**
 * 구글 REST API 직접 연동. 인가 코드를 토큰으로 교환하면 함께 오는 ID Token 을
 * [GoogleIdTokenValidator] 로 로컬 검증해 신원을 얻는다(별도 사용자정보 API 호출 없음).
 *
 * 전환 규칙 — form 파라미터 6개의 이름과 추가 순서를 그대로 유지한다. 구글은 카카오와 달리
 * `client_secret` 을 **항상** 추가한다(조건 분기 없음) — 이 차이도 원본 그대로다.
 */
@Component
class GoogleOAuthClient(
    oauthWebClient: WebClient,
    private val idTokenValidator: GoogleIdTokenValidator,
    @param:Value("\${oauth.google.client-id}") private val clientId: String,
    @param:Value("\${oauth.google.client-secret}") private val clientSecret: String,
    @param:Value("\${oauth.google.token-uri}") private val tokenUri: String,
    @param:Value("\${oauth.http.response-timeout-millis}") responseTimeoutMillis: Long,
) : OAuthClient {
    private val webClient: WebClient = oauthWebClient
    private val responseTimeout: Duration = Duration.ofMillis(responseTimeoutMillis)

    override fun provider(): OAuthProvider = OAuthProvider.GOOGLE

    override fun resolveIdentity(
        code: String?,
        codeVerifier: String?,
        redirectUri: String?,
        oidcNonce: String?,
    ): OAuthUserIdentity {
        val idToken = exchangeToken(code, codeVerifier, redirectUri)
        val jwt = idTokenValidator.validate(idToken, oidcNonce)

        val email = jwt.getClaimAsString("email")
        if (email.isNullOrBlank()) {
            throw BusinessException(ErrorCode.OAUTH_EMAIL_NOT_PROVIDED)
        }
        return OAuthUserIdentity(OAuthProvider.GOOGLE, jwt.subject, email)
    }

    private fun exchangeToken(
        code: String?,
        codeVerifier: String?,
        redirectUri: String?,
    ): String {
        val form: MultiValueMap<String, String> = LinkedMultiValueMap()
        form.add("grant_type", "authorization_code")
        form.add("client_id", clientId)
        form.add("client_secret", clientSecret)
        form.add("redirect_uri", redirectUri)
        form.add("code", code)
        form.add("code_verifier", codeVerifier)

        val response =
            OAuthClientErrorMapper.call(
                Supplier {
                    webClient
                        .post()
                        .uri(tokenUri)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(BodyInserters.fromFormData(form))
                        .retrieve()
                        .bodyToMono(GoogleTokenResponse::class.java)
                        .timeout(responseTimeout)
                        .block()
                },
            )

        if (response?.idToken.isNullOrBlank()) {
            throw BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR)
        }
        return response!!.idToken!!
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GoogleTokenResponse(
        val idToken: String? = null,
    )
}
