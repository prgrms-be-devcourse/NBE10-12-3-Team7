package com.dongnemarket.auth.client

import com.dongnemarket.auth.entity.OAuthProvider

/**
 * 제공자별 인가 코드 교환 + 사용자 신원 확인을 감춘 추상화. 카카오는 토큰 교환 후 별도 사용자정보 API 를
 * 호출하고, 구글은 토큰 교환 응답에 포함된 ID Token 을 로컬 검증해 신원을 얻는다 — 호출부는 그 차이를
 * 몰라도 된다.
 *
 * 전환 규칙 — 단일 추상 메서드가 아니므로 `fun interface` 논의 자체가 없고, default method 도 추가하지 않는다.
 * 파라미터는 원본 Java 가 전부 참조형이라 nullable 로 둔다(non-null 로 조여도 descriptor 는 같지만
 * 구현체에 런타임 null 검사가 삽입돼 아직 Java 인 호출부의 동작이 달라질 수 있다).
 */
interface OAuthClient {
    fun provider(): OAuthProvider

    /**
     * @param code 제공자 인가 코드
     * @param codeVerifier PKCE code_verifier(Redis state record 에서 가져온 값)
     * @param redirectUri 이 인가 시도에 사용된 redirect_uri(발급 시 저장된 값과 동일해야 제공자가 코드를 인정한다)
     * @param oidcNonce 구글만 사용(ID Token 의 nonce 클레임 검증용). 카카오 구현체는 무시한다.
     * @throws com.dongnemarket.global.exception.BusinessException
     *     `OAUTH_AUTHORIZATION_FAILED`(제공자가 코드를 거부 — 4xx),
     *     `OAUTH_PROVIDER_ERROR`(제공자 5xx/네트워크/timeout),
     *     `OAUTH_EMAIL_NOT_PROVIDED`, `OAUTH_EMAIL_NOT_VERIFIED`
     */
    fun resolveIdentity(
        code: String?,
        codeVerifier: String?,
        redirectUri: String?,
        oidcNonce: String?,
    ): OAuthUserIdentity
}
