package com.dongnemarket.auth.client

import com.dongnemarket.auth.entity.OAuthProvider
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.function.Supplier

/**
 * 카카오 REST API 직접 연동. 인가 코드를 액세스 토큰으로 교환한 뒤, 그 토큰으로 사용자정보를 조회한다
 * (카카오는 OIDC ID Token 을 쓰지 않는 REST API 흐름이라 이 프로젝트에서는 별도 검증 없이 access_token
 * 기반 사용자정보 API 만 사용한다).
 *
 * 전환 규칙 — **form 파라미터 이름·추가 순서, 헤더, Content-Type, 응답 JSON 필드명을 그대로 유지한다.**
 * `client_secret` 은 원본과 동일하게 **비어 있지 않을 때만** 추가한다(카카오는 선택 항목).
 * 응답 DTO 는 `@JsonNaming(SnakeCaseStrategy)` + `@JsonIgnoreProperties(ignoreUnknown = true)` 를 유지해
 * snake_case 매핑과 unknown 필드 무시 동작이 바뀌지 않게 한다.
 * 필드는 전부 nullable 이다 — 원본 Java record 가 참조형이고, null 일 때 정해진 ErrorCode 로 실패하는
 * 흐름이 계약이라 non-null 로 조여 역직렬화 시점 예외로 바꾸면 오류 종류가 달라진다.
 */
@Component
class KakaoOAuthClient(
    oauthWebClient: WebClient,
    @param:Value("\${oauth.kakao.client-id}") private val clientId: String,
    @param:Value("\${oauth.kakao.client-secret}") private val clientSecret: String?,
    @param:Value("\${oauth.kakao.token-uri}") private val tokenUri: String,
    @param:Value("\${oauth.kakao.user-info-uri}") private val userInfoUri: String,
    @param:Value("\${oauth.http.response-timeout-millis}") responseTimeoutMillis: Long,
) : OAuthClient {
    private val webClient: WebClient = oauthWebClient
    private val responseTimeout: Duration = Duration.ofMillis(responseTimeoutMillis)

    override fun provider(): OAuthProvider = OAuthProvider.KAKAO

    override fun resolveIdentity(
        code: String?,
        codeVerifier: String?,
        redirectUri: String?,
        oidcNonce: String?,
    ): OAuthUserIdentity {
        val accessToken = exchangeToken(code, codeVerifier, redirectUri)
        val user = fetchUser(accessToken)
        return toIdentity(user)
    }

    private fun exchangeToken(
        code: String?,
        codeVerifier: String?,
        redirectUri: String?,
    ): String {
        val form: MultiValueMap<String, String> = LinkedMultiValueMap()
        form.add("grant_type", "authorization_code")
        form.add("client_id", clientId)
        form.add("redirect_uri", redirectUri)
        form.add("code", code)
        form.add("code_verifier", codeVerifier)
        if (!clientSecret.isNullOrBlank()) {
            form.add("client_secret", clientSecret)
        }

        val response =
            OAuthClientErrorMapper.call(
                Supplier {
                    webClient
                        .post()
                        .uri(tokenUri)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(BodyInserters.fromFormData(form))
                        .retrieve()
                        .bodyToMono(KakaoTokenResponse::class.java)
                        .timeout(responseTimeout)
                        .block()
                },
            )

        if (response?.accessToken.isNullOrBlank()) {
            throw BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR)
        }
        return response!!.accessToken!!
    }

    private fun fetchUser(accessToken: String): KakaoUserResponse {
        val response =
            OAuthClientErrorMapper.call(
                Supplier {
                    webClient
                        .get()
                        .uri(userInfoUri)
                        .headers { headers -> headers.setBearerAuth(accessToken) }
                        .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded;charset=utf-8")
                        .retrieve()
                        .bodyToMono(KakaoUserResponse::class.java)
                        .timeout(responseTimeout)
                        .block()
                },
            )

        return response ?: throw BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR)
    }

    private fun toIdentity(user: KakaoUserResponse): OAuthUserIdentity {
        if (user.id == null) {
            throw BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR)
        }
        val account = user.kakaoAccount
        if (account?.email.isNullOrBlank()) {
            throw BusinessException(ErrorCode.OAUTH_EMAIL_NOT_PROVIDED)
        }
        val verified = account!!.isEmailValid == true && account.isEmailVerified == true
        if (!verified) {
            throw BusinessException(ErrorCode.OAUTH_EMAIL_NOT_VERIFIED)
        }
        return OAuthUserIdentity(OAuthProvider.KAKAO, user.id.toString(), account.email)
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class KakaoTokenResponse(
        val accessToken: String? = null,
    )

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class KakaoUserResponse(
        val id: Long? = null,
        val kakaoAccount: KakaoAccount? = null,
    ) {
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class KakaoAccount(
            val email: String? = null,
            val isEmailValid: Boolean? = null,
            val isEmailVerified: Boolean? = null,
        )
    }
}
