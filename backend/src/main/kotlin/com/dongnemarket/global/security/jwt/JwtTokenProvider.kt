package com.dongnemarket.global.security.jwt

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

/**
 * JWT 발급/검증/파싱.
 *
 * auth 도메인은 로그인 성공 시 [createAccessToken] 으로 토큰을 발급한다.
 * role 값은 `"ROLE_USER"` / `"ROLE_ADMIN"` 처럼 권한 prefix 를 포함해 전달한다
 * (SecurityConfig 의 `hasRole("ADMIN")` 매칭).
 *
 * 생성자 파라미터에 `val` 을 붙이지 않은 것은 의도적이다 — 원본 값을 그대로 보관하지 않고
 * 아래 프로퍼티 초기화식에서 가공(바이트 키 생성, 초→밀리초)한 결과만 남긴다.
 */
@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.access-token-validity-seconds}") accessTokenValiditySeconds: Long,
    @Value("\${jwt.refresh-token-validity-seconds}") refreshTokenValiditySeconds: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    private val accessTokenValidityMillis: Long = accessTokenValiditySeconds * 1000L
    private val refreshTokenValidityMillis: Long = refreshTokenValiditySeconds * 1000L

    /**
     * 로그인 성공 시 호출: memberId(subject) + role 클레임으로 액세스 토큰 발급.
     *
     * `memberId` 가 `Long?` 인 것은 원본 Java 시그니처(`Long`, 박싱 타입)를 그대로 옮긴 결과다.
     * non-null `Long` 으로 조이면 JVM 에서 primitive `long` 이 되어, 아직 Java 인 호출부의
     * `member.getId()` 가 null 일 때 자동 언박싱 NPE 가 난다(AuthServiceTest 에서 실제로 터졌다).
     *
     * 점진 전환 중에는 **공개 API 의 nullability 를 임의로 조이지 않는다** — 조이면 다른 담당자
     * 영역이 깨진다. 전부 Kotlin 이 된 뒤 별도 패스에서 조인다.
     */
    fun createAccessToken(
        memberId: Long?,
        role: String,
    ): String {
        val now = Date()
        val expiry = Date(now.time + accessTokenValidityMillis)
        return Jwts
            .builder()
            .subject(memberId.toString())
            .claim("role", role)
            .claim(CLAIM_TYPE, TOKEN_TYPE_ACCESS)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(key)
            .compact()
    }

    /**
     * 로그인 성공 시 호출: memberId(subject)만 담아 리프레시 토큰 발급. nullability 는 [createAccessToken] 참고.
     *
     * `jti`(UUID)를 넣는 이유 — 나머지 클레임(sub/type/iat/exp)은 초 단위라, 같은 회원이 같은 초에
     * 재발급하면 이전과 동일한 토큰이 나와 회전(rotation)의 "구 토큰 재사용 거부"가 무력화된다.
     * 재사용 판정은 저장소의 문자열 비교(RefreshToken.matches)이므로 jti 를 별도로 읽는 코드는 없다.
     */
    fun createRefreshToken(memberId: Long?): String {
        val now = Date()
        val expiry = Date(now.time + refreshTokenValidityMillis)
        return Jwts
            .builder()
            .id(UUID.randomUUID().toString())
            .subject(memberId.toString())
            .claim(CLAIM_TYPE, TOKEN_TYPE_REFRESH)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(key)
            .compact()
    }

    /** 토큰의 만료 시각(java.time). RefreshToken 저장 시 expiresAt 계산에 사용 */
    fun getExpiration(token: String): LocalDateTime {
        val expiration = parse(token).expiration
        return LocalDateTime.ofInstant(expiration.toInstant(), ZoneId.systemDefault())
    }

    /** 토큰 유효성 검증 (서명/만료/형식) */
    fun validateToken(token: String): Boolean =
        try {
            parse(token)
            true
        } catch (e: Exception) {
            false
        }

    /**
     * 토큰 → 인증 객체 (principal = memberId, authorities = [role]).
     * Access Token(`type=access`)이 아니면 인증에 사용할 수 없다(Refresh Token 오용 방지).
     */
    fun getAuthentication(token: String): Authentication {
        val claims = parse(token)
        if (TOKEN_TYPE_ACCESS != claims.get(CLAIM_TYPE, String::class.java)) {
            throw JwtException("Access Token이 아닙니다.")
        }
        val memberId = claims.subject.toLong()
        val role = claims.get("role", String::class.java)
        val authorities: Collection<GrantedAuthority> =
            if (role == null) emptyList() else listOf(SimpleGrantedAuthority(role))
        return UsernamePasswordAuthenticationToken(memberId, token, authorities)
    }

    /** 토큰에서 memberId 추출 */
    fun getMemberId(token: String): Long = parse(token).subject.toLong()

    /** Refresh Token(`type=refresh`)인지 여부 */
    fun isRefreshToken(token: String): Boolean = TOKEN_TYPE_REFRESH == parse(token).get(CLAIM_TYPE, String::class.java)

    private fun parse(token: String): Claims =
        Jwts
            .parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload

    companion object {
        private const val CLAIM_TYPE = "type"
        private const val TOKEN_TYPE_ACCESS = "access"
        private const val TOKEN_TYPE_REFRESH = "refresh"
    }
}
