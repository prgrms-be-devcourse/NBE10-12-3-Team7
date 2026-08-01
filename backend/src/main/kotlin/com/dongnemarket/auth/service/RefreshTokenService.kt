package com.dongnemarket.auth.service

import com.dongnemarket.auth.entity.RefreshToken
import com.dongnemarket.auth.repository.RefreshTokenRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Refresh Token 의 저장·교체·검증·삭제를 전담한다.
 *
 * 회원당 1개만 유지하며(단일 세션), 재로그인/재발급 시 기존 토큰을 교체한다.
 * `AuthService` 는 이 클래스만 의존하고 [RefreshTokenRepository] 를 직접 참조하지 않는다 —
 * 저장소가 프로파일에 따라 JPA/Redis 로 갈려도 이 클래스 내부만 바뀌면 되고,
 * Controller/AuthService 는 영향받지 않는다.
 *
 * 장애 정책: 로그인·재발급은 fail-closed 다(저장소 접근 실패 시 예외를 그대로 전파해 API 자체를 실패시킨다 —
 * 별도 try/catch 를 두지 않는 것 자체가 정책이다). 로그아웃만 예외로, 멱등성을 우선해 저장소 삭제가
 * 실패해도 예외를 던지지 않는다([deleteByMemberId] 참고).
 *
 * 전환 규칙 — 클래스 레벨 `@Transactional` 과 [validateAndGetMemberId] 의
 * `@Transactional(readOnly = true)` **위치를 그대로 유지한다.** 검증 순서(JWT 파싱 → refresh 타입 확인 →
 * 저장소 조회 → 문자열 일치)와 catch 순서(`ExpiredJwtException` → `JwtException`/`IllegalArgumentException`)도
 * 원본 그대로다 — 순서가 바뀌면 만료 토큰이 INVALID 로 매핑된다.
 */
@Service
@Transactional
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    /**
     * 로그인 성공 시 호출: 항상 save() 를 호출해 저장소에 최신 토큰을 반영한다. insert/update(있으면 교체,
     * 없으면 신규) 분기는 각 구현체(JPA/Redis) 내부 책임이라 여기서는 나누지 않는다.
     *
     * fail-closed: 저장 실패 시(Redis 장애 등) 예외를 그대로 전파해 로그인 자체를 실패시킨다.
     */
    fun saveOrReplace(
        memberId: Long?,
        token: String?,
    ) {
        val expiresAt = jwtTokenProvider.getExpiration(token!!)
        refreshTokenRepository.save(RefreshToken.issue(memberId, token, expiresAt))
    }

    /**
     * Refresh Token 을 검증하고 memberId 를 반환한다. fail-closed: 저장소 조회 실패 시(Redis 장애 등)
     * 예외를 그대로 전파해 재발급 자체를 실패시킨다.
     * 검증 순서: 서명/만료(JWT) → Refresh Token 타입 여부(Access Token 오용 방지) → 저장소에 값 존재 → 저장값과 문자열 일치.
     *
     * 반환 타입이 nullable `Long?` 인 이유 — 원본 Java 가 boxed `Long` 을 반환했다. non-null `Long` 으로
     * 두면 primitive `long` 이 되어 descriptor 가 바뀐다(계약 테스트가 이 회귀를 실제로 잡았다).
     */
    @Transactional(readOnly = true)
    fun validateAndGetMemberId(refreshToken: String?): Long? {
        val memberId: Long
        try {
            memberId = jwtTokenProvider.getMemberId(refreshToken!!)
            if (!jwtTokenProvider.isRefreshToken(refreshToken!!)) {
                throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
            }
        } catch (e: ExpiredJwtException) {
            throw BusinessException(ErrorCode.EXPIRED_REFRESH_TOKEN)
        } catch (e: JwtException) {
            throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        }

        val saved =
            refreshTokenRepository
                .findByMemberId(memberId)
                .orElseThrow { BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND) }
        if (!saved.matches(refreshToken)) {
            throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        }
        return memberId
    }

    /**
     * 로그아웃 시 호출: 저장된 Refresh Token 을 삭제한다.
     * 이미 삭제되어 저장된 row/키가 없어도 예외 없이 통과한다(멱등 — 중복 로그아웃 허용).
     *
     * 다른 메서드와 달리 fail-closed 가 아니다 — 저장소 삭제 자체가 실패해도(Redis 장애 등) 예외를
     * 던지지 않고 로그만 남긴다. 호출자(AuthController)가 이 메서드 실패 여부와 무관하게 항상 쿠키를
     * 만료시키고 200 을 반환할 수 있어야 하기 때문이다. 삭제되지 못한 값은 TTL 로 자연 만료된다.
     */
    fun deleteByMemberId(memberId: Long?) {
        try {
            refreshTokenRepository.deleteByMemberId(memberId)
        } catch (e: DataAccessException) {
            log.error(
                "Refresh Token 삭제 실패(memberId={}). 저장소에 토큰이 남아있을 수 있다(TTL 만료로 자연 정리됨).",
                memberId,
                e,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RefreshTokenService::class.java)
    }
}
