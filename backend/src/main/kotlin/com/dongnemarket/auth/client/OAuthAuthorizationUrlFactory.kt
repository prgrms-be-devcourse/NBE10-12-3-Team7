package com.dongnemarket.auth.client

import com.dongnemarket.auth.entity.OAuthProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder

/**
 * 카카오/구글 인가 화면 URL 을 조립한다. 프론트가 쿼리 파라미터를 직접 구성하지 않도록, 이 팩토리가
 * 완성된 URL 을 만든다(scope·response_type·PKCE 파라미터 이름 등을 프론트가 알 필요가 없다).
 *
 * 전환 규칙 — **쿼리 파라미터 이름·값·추가 순서를 원본 그대로 유지한다.** `scope` 문자열
 * (`account_email` / `openid email`), `response_type=code`, `code_challenge_method=S256` 은
 * 제공자와의 계약이라 한 글자도 바꾸지 않는다.
 */
@Component
class OAuthAuthorizationUrlFactory(
    @param:Value("\${oauth.kakao.authorization-uri}") private val kakaoAuthorizationUri: String,
    @param:Value("\${oauth.kakao.client-id}") private val kakaoClientId: String,
    @param:Value("\${oauth.kakao.redirect-uri}") private val kakaoRedirectUri: String,
    @param:Value("\${oauth.google.authorization-uri}") private val googleAuthorizationUri: String,
    @param:Value("\${oauth.google.client-id}") private val googleClientId: String,
    @param:Value("\${oauth.google.redirect-uri}") private val googleRedirectUri: String,
) {
    fun redirectUri(provider: OAuthProvider): String =
        when (provider) {
            OAuthProvider.KAKAO -> kakaoRedirectUri
            OAuthProvider.GOOGLE -> googleRedirectUri
        }

    /** @param oidcNonce 구글만 사용. 카카오는 null 이면 nonce 파라미터를 붙이지 않는다. */
    fun build(
        provider: OAuthProvider,
        state: String?,
        codeChallenge: String?,
        oidcNonce: String?,
    ): String =
        when (provider) {
            OAuthProvider.KAKAO ->
                UriComponentsBuilder
                    .fromUriString(kakaoAuthorizationUri)
                    .queryParam("client_id", kakaoClientId)
                    .queryParam("redirect_uri", kakaoRedirectUri)
                    .queryParam("response_type", "code")
                    .queryParam("scope", "account_email")
                    .queryParam("state", state)
                    .queryParam("code_challenge", codeChallenge)
                    .queryParam("code_challenge_method", "S256")
                    .build()
                    .toUriString()

            OAuthProvider.GOOGLE ->
                UriComponentsBuilder
                    .fromUriString(googleAuthorizationUri)
                    .queryParam("client_id", googleClientId)
                    .queryParam("redirect_uri", googleRedirectUri)
                    .queryParam("response_type", "code")
                    .queryParam("scope", "openid email")
                    .queryParam("state", state)
                    .queryParam("nonce", oidcNonce)
                    .queryParam("code_challenge", codeChallenge)
                    .queryParam("code_challenge_method", "S256")
                    .build()
                    .toUriString()
        }
}
