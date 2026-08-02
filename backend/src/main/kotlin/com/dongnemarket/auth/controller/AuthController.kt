package com.dongnemarket.auth.controller

import com.dongnemarket.auth.client.GoogleOAuthClient
import com.dongnemarket.auth.client.KakaoOAuthClient
import com.dongnemarket.auth.client.OAuthClient
import com.dongnemarket.auth.dto.AccessTokenResponse
import com.dongnemarket.auth.dto.LoginRequest
import com.dongnemarket.auth.dto.OAuthAuthorizationStart
import com.dongnemarket.auth.dto.OAuthLoginRequest
import com.dongnemarket.auth.dto.SignupRequest
import com.dongnemarket.auth.dto.SignupResponse
import com.dongnemarket.auth.service.AuthService
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

/**
 * 전환 규칙 (7단계) — endpoint 파라미터의 nullability 는 Spring MVC binding 의미를 기준으로 정한다.
 *
 * - `@Valid @RequestBody` 파라미터는 **non-null 로 유지한다.** Kotlin nullable 로 두면 Spring 이
 *   `MethodParameter.isOptional()` 로 body 를 선택 사항으로 해석해, 원본 Java 의 "body 누락 → 400
 *   (`HttpMessageNotReadableException`)" 계약이 null 통과로 바뀐다.
 * - `@CookieValue(required = false)` 는 쿠키가 없으면 Spring 이 null 을 넣으므로 `String?` 이어야 한다.
 * - `@AuthenticationPrincipal` 은 원본이 boxed `Long` 이고 principal 미존재 시 null 이 전달되므로 `Long?`.
 * - `HttpServletRequest`/`HttpServletResponse` 는 Spring MVC 가 항상 주입하므로 non-null.
 */
@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val kakaoOAuthClient: KakaoOAuthClient,
    private val googleOAuthClient: GoogleOAuthClient,
    @param:Value("\${jwt.refresh-token-validity-seconds}") private val refreshTokenValiditySeconds: Long,
    @param:Value("\${auth.cookie.secure:false}") private val cookieSecure: Boolean,
    @param:Value("\${oauth-bcid-cookie.max-age-seconds}") private val oauthBcidCookieMaxAgeSeconds: Long,
) {
    private val secureRandom = SecureRandom()

    @Operation(
        summary = "회원가입",
        description =
            "이메일/비밀번호/닉네임으로 회원가입을 진행한다. 이용약관·개인정보 수집 및 이용 동의는 필수이며, " +
                "동의 이력(버전/동의시각/IP/User-Agent)이 함께 저장된다.",
    )
    @PostMapping("/signup")
    fun signup(
        @Valid @RequestBody request: SignupRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<SignupResponse>> {
        val response =
            authService.signup(
                request,
                extractIpAddress(httpRequest),
                httpRequest.getHeader("User-Agent"),
            )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(HttpStatus.CREATED.value(), "회원가입이 완료되었습니다.", response))
    }

    /** 리버스 프록시(nginx 등) 뒤에 있으면 X-Forwarded-For의 첫 값을 우선한다. 동의 이력 증적용이라 엄격한 신뢰 검증까지는 하지 않는다. */
    private fun extractIpAddress(httpRequest: HttpServletRequest): String {
        val forwardedFor = httpRequest.getHeader("X-Forwarded-For")
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim()
        }
        return httpRequest.remoteAddr
    }

    @Operation(
        summary = "로그인",
        description =
            "이메일/비밀번호로 로그인하고 JWT Access Token을 발급한다. Refresh Token은 HttpOnly 쿠키로 내려간다 " +
                "(autoLogin=true면 Max-Age가 있는 영속 쿠키, false면 브라우저 종료 시 사라지는 세션 쿠키). " +
                "동일 이메일로 5회 연속 실패하면 10분간 로그인이 차단된다(429 AUTH_019).",
    )
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<ApiResponse<AccessTokenResponse>> {
        val response = authService.login(request)
        val maxAge = if (request.autoLogin) Duration.ofSeconds(refreshTokenValiditySeconds) else null
        setRefreshTokenCookie(httpResponse, response.refreshToken, maxAge)
        return ResponseEntity.ok(ApiResponse.success("로그인이 완료되었습니다.", AccessTokenResponse.of(response.accessToken)))
    }

    @Operation(
        summary = "Access Token 재발급",
        description =
            "HttpOnly 쿠키의 Refresh Token으로 Access Token을 재발급한다. " +
                "Refresh Token도 함께 회전(rotation)되며, 새 Refresh Token이 HttpOnly 쿠키로 다시 내려간다.",
    )
    @PostMapping("/reissue")
    fun reissue(
        @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) refreshToken: String?,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<ApiResponse<AccessTokenResponse>> {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        }
        val response = authService.reissue(refreshToken)
        setRefreshTokenCookie(httpResponse, response.refreshToken, Duration.ofSeconds(refreshTokenValiditySeconds))
        return ResponseEntity.ok(ApiResponse.success("토큰이 재발급되었습니다.", AccessTokenResponse.of(response.accessToken)))
    }

    @Operation(summary = "로그아웃", description = "현재 로그인한 사용자의 Refresh Token을 삭제하고 쿠키를 만료시킨다. 여러 번 호출해도 항상 성공한다.")
    @PostMapping("/logout")
    fun logout(
        @AuthenticationPrincipal memberId: Long?,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<ApiResponse<Void>> {
        authService.logout(memberId)
        setRefreshTokenCookie(httpResponse, "", Duration.ZERO)
        return ResponseEntity.ok(ApiResponse.success())
    }

    /**
     * Refresh Token을 HttpOnly 쿠키로 내려보낸다. Domain은 지정하지 않아 Host-Only 쿠키로 유지한다.
     * @param maxAge null이면 Max-Age/Expires를 지정하지 않는 세션 쿠키(브라우저 종료 시 삭제)로 발급한다.
     */
    private fun setRefreshTokenCookie(
        httpResponse: HttpServletResponse,
        value: String?,
        maxAge: Duration?,
    ) {
        val builder =
            ResponseCookie
                .from(REFRESH_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
        if (maxAge != null) {
            builder.maxAge(maxAge)
        }
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString())
    }

    @Operation(
        summary = "카카오 로그인 시작",
        description =
            "카카오 인가 화면으로 리다이렉트할 완성된 URL(PKCE S256 포함)과 state를 발급한다. " +
                "브라우저 귀속 쿠키(oauth_bcid)가 없으면 새로 내려보내고, 있으면 재사용한다(여러 탭에서 동시에 로그인을 시작해도 서로 방해하지 않는다).",
    )
    @PostMapping("/oauth/kakao/authorization")
    fun kakaoAuthorization(
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<ApiResponse<OAuthAuthorizationStart>> = startAuthorization(kakaoOAuthClient, httpRequest, httpResponse)

    @Operation(summary = "구글 로그인 시작", description = "구글 인가 화면으로 리다이렉트할 완성된 URL(PKCE S256 + OIDC nonce 포함)과 state를 발급한다.")
    @PostMapping("/oauth/google/authorization")
    fun googleAuthorization(
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<ApiResponse<OAuthAuthorizationStart>> = startAuthorization(googleOAuthClient, httpRequest, httpResponse)

    @Operation(
        summary = "카카오 로그인 완료",
        description =
            "카카오가 돌려준 인가 코드와 state로 로그인을 완료한다. " +
                "기존 연동이 있으면 로그인, 없고 이메일도 겹치지 않으면 신규가입 후 로그인한다. " +
                "이메일이 기존 계정(로컬 또는 다른 provider)과 겹치면 409로 거부한다.",
    )
    @PostMapping("/oauth/kakao/login")
    fun kakaoLogin(
        @Valid @RequestBody request: OAuthLoginRequest,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<ApiResponse<AccessTokenResponse>> = oauthLogin(kakaoOAuthClient, request, httpRequest, httpResponse)

    @Operation(summary = "구글 로그인 완료", description = "구글이 돌려준 인가 코드와 state로 로그인을 완료한다. ID Token 서명·클레임을 서버에서 직접 검증한다.")
    @PostMapping("/oauth/google/login")
    fun googleLogin(
        @Valid @RequestBody request: OAuthLoginRequest,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<ApiResponse<AccessTokenResponse>> = oauthLogin(googleOAuthClient, request, httpRequest, httpResponse)

    private fun startAuthorization(
        client: OAuthClient,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<ApiResponse<OAuthAuthorizationStart>> {
        val browserCorrelationId = resolveOrCreateBrowserCorrelationId(httpRequest, httpResponse)
        val start = authService.startAuthorization(client, sha256Hex(browserCorrelationId))
        return ResponseEntity.ok(ApiResponse.success(start))
    }

    private fun oauthLogin(
        client: OAuthClient,
        request: OAuthLoginRequest,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<ApiResponse<AccessTokenResponse>> {
        val browserCorrelationId = requireBrowserCorrelationId(httpRequest)
        val response =
            authService.oauthLogin(
                client,
                request.getCode(),
                request.getState(),
                sha256Hex(browserCorrelationId),
            )
        setRefreshTokenCookie(httpResponse, response.refreshToken, Duration.ofSeconds(refreshTokenValiditySeconds))
        return ResponseEntity.ok(ApiResponse.success("로그인이 완료되었습니다.", AccessTokenResponse.of(response.accessToken)))
    }

    /**
     * `oauth_bcid` 쿠키가 이미 있으면 그대로 재사용(재발급하지 않음 — 여러 탭이 같은 브라우저로
     * 묶여야 한다), 없으면 새로 발급한다. 어느 쪽이든 Max-Age를 갱신해 다시 내려보낸다.
     */
    private fun resolveOrCreateBrowserCorrelationId(
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): String {
        val existing = readBrowserCorrelationCookie(httpRequest)
        val value = existing ?: generateBrowserCorrelationId()
        setBrowserCorrelationCookie(httpResponse, value)
        return value
    }

    /** 로그인 완료 엔드포인트에서는 쿠키가 없으면 새로 만들지 않는다 — 애초에 이 브라우저로 발급된 state가 있을 수 없다. */
    private fun requireBrowserCorrelationId(httpRequest: HttpServletRequest): String =
        readBrowserCorrelationCookie(httpRequest)
            ?: throw BusinessException(ErrorCode.INVALID_OAUTH_STATE)

    private fun readBrowserCorrelationCookie(httpRequest: HttpServletRequest): String? {
        val cookies = httpRequest.cookies ?: return null
        for (cookie in cookies) {
            if (OAUTH_BCID_COOKIE == cookie.name && !cookie.value.isBlank()) {
                return cookie.value
            }
        }
        return null
    }

    private fun generateBrowserCorrelationId(): String {
        val bytes = ByteArray(BCID_RANDOM_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * OAuth 전용 쿠키. 한 탭의 로그인이 끝났다고 여기서 지우지 않는다(다른 탭이 진행 중일 수 있음) —
     * state별 사용 여부는 Redis state record가 관리한다. Path를 OAuth API 범위로 제한하고, Domain은
     * 지정하지 않아 host-only 쿠키로 유지한다.
     */
    private fun setBrowserCorrelationCookie(
        httpResponse: HttpServletResponse,
        value: String,
    ) {
        val cookie =
            ResponseCookie
                .from(OAUTH_BCID_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(OAUTH_COOKIE_PATH)
                .maxAge(Duration.ofSeconds(oauthBcidCookieMaxAgeSeconds))
                .build()
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }

    /**
     * `toByteArray(Charset.defaultCharset())` 인 이유 — 원본 Java 의 인자 없는 `String.getBytes()` 는
     * 플랫폼 기본 charset 을 쓴다. Kotlin 의 인자 없는 `toByteArray()` 는 UTF-8 고정이라 의미가 다르다
     * (BCID 는 base64url ASCII 라 실제 바이트는 같지만, 원본 의미를 그대로 옮긴다).
     */
    private fun sha256Hex(value: String): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashed = digest.digest(value.toByteArray(Charset.defaultCharset()))
            return HexFormat.of().formatHex(hashed)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e)
        }
    }

    companion object {
        private const val REFRESH_TOKEN_COOKIE = "refreshToken"
        private const val OAUTH_BCID_COOKIE = "oauth_bcid"
        private const val OAUTH_COOKIE_PATH = "/api/auth/oauth"
        private const val BCID_RANDOM_BYTES = 32
    }
}
