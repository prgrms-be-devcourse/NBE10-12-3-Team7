package com.dongnemarket.auth.service

import com.dongnemarket.auth.entity.RefreshToken
import com.dongnemarket.auth.repository.RefreshTokenRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.RedisConnectionFailureException
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class RefreshTokenServiceTest {
    @Mock
    lateinit var refreshTokenRepository: RefreshTokenRepository

    lateinit var jwtTokenProvider: JwtTokenProvider
    lateinit var refreshTokenService: RefreshTokenService

    @BeforeEach
    fun setUp() {
        jwtTokenProvider =
            JwtTokenProvider("test-jwt-secret-key-for-refresh-token-service-unit-test-0123456789", 3600L, 604800L)
        refreshTokenService = RefreshTokenService(refreshTokenRepository, jwtTokenProvider)
    }

    // ===== saveOrReplace =====
    // insert/update(있으면 교체, 없으면 신규) 분기는 더 이상 서비스가 아니라 각 저장소 구현체(JPA/Redis)의
    // 책임이다(JPA는 findByMemberId 후 mutate, Redis는 SET이 원래 덮어쓰기라 분기 자체가 없음). 그래서
    // 서비스 레벨 테스트는 "항상 save()를 호출하는지"와 "정확한 값을 넘기는지"만 검증한다.

    @Test
    @DisplayName("saveOrReplace를 호출하면 항상 저장소에 save()한다")
    fun saveOrReplace_alwaysCallsSave() {
        val token = jwtTokenProvider.createRefreshToken(1L)

        refreshTokenService.saveOrReplace(1L, token)

        verify(refreshTokenRepository).save(any(RefreshToken::class.java))
    }

    @Test
    @DisplayName("saveOrReplace는 주어진 memberId·token을 그대로 저장소에 전달한다(재로그인 시에도 새 값으로 덮어써진다)")
    fun saveOrReplace_passesGivenMemberIdAndToken() {
        val token = jwtTokenProvider.createRefreshToken(1L)

        refreshTokenService.saveOrReplace(1L, token)

        val captor: ArgumentCaptor<RefreshToken> = ArgumentCaptor.forClass(RefreshToken::class.java)
        verify(refreshTokenRepository).save(captor.capture())
        assertThat(captor.value.memberId).isEqualTo(1L)
        assertThat(captor.value.token).isEqualTo(token)
    }

    // ===== validateAndGetMemberId =====

    @Test
    @DisplayName("서명·만료가 유효하고 DB 저장값과 일치하면 memberId를 반환한다")
    fun validateAndGetMemberId_success() {
        val token = jwtTokenProvider.createRefreshToken(1L)
        given(refreshTokenRepository.findByMemberId(1L))
            .willReturn(Optional.of(RefreshToken.issue(1L, token, LocalDateTime.now().plusDays(7))))

        val memberId = refreshTokenService.validateAndGetMemberId(token)

        assertThat(memberId).isEqualTo(1L)
    }

    @Test
    @DisplayName("만료된 토큰이면 EXPIRED_REFRESH_TOKEN 예외가 발생한다")
    fun validateAndGetMemberId_expired_throwsExpiredRefreshToken() {
        val expiredProvider =
            JwtTokenProvider("test-jwt-secret-key-for-refresh-token-service-unit-test-0123456789", 3600L, 0L)
        val expiredToken = expiredProvider.createRefreshToken(1L)

        assertThatThrownBy { refreshTokenService.validateAndGetMemberId(expiredToken) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXPIRED_REFRESH_TOKEN)
    }

    @Test
    @DisplayName("다른 키로 서명된(위조) 토큰이면 INVALID_REFRESH_TOKEN 예외가 발생한다")
    fun validateAndGetMemberId_forgedSignature_throwsInvalidRefreshToken() {
        val otherProvider =
            JwtTokenProvider("a-totally-different-secret-key-for-forgery-0123456789", 3600L, 604800L)
        val forgedToken = otherProvider.createRefreshToken(1L)

        assertThatThrownBy { refreshTokenService.validateAndGetMemberId(forgedToken) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN)
    }

    @Test
    @DisplayName("서명·만료는 정상이나 DB에 저장된 row가 없으면 REFRESH_TOKEN_NOT_FOUND 예외가 발생한다")
    fun validateAndGetMemberId_notFoundInDb_throwsRefreshTokenNotFound() {
        val token = jwtTokenProvider.createRefreshToken(1L)
        given(refreshTokenRepository.findByMemberId(1L)).willReturn(Optional.empty())

        assertThatThrownBy { refreshTokenService.validateAndGetMemberId(token) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFRESH_TOKEN_NOT_FOUND)
    }

    @Test
    @DisplayName("DB에 저장된 값과 다른 토큰이면 INVALID_REFRESH_TOKEN 예외가 발생한다")
    fun validateAndGetMemberId_tokenMismatch_throwsInvalidRefreshToken() {
        val token = jwtTokenProvider.createRefreshToken(1L)
        given(refreshTokenRepository.findByMemberId(1L))
            .willReturn(Optional.of(RefreshToken.issue(1L, "different-stored-token", LocalDateTime.now().plusDays(7))))

        assertThatThrownBy { refreshTokenService.validateAndGetMemberId(token) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN)
    }

    @Test
    @DisplayName("Access Token을 Refresh Token 자리에 제시하면 INVALID_REFRESH_TOKEN 예외가 발생한다")
    fun validateAndGetMemberId_accessTokenPresented_throwsInvalidRefreshToken() {
        val accessToken = jwtTokenProvider.createAccessToken(1L, "ROLE_USER")

        assertThatThrownBy { refreshTokenService.validateAndGetMemberId(accessToken) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN)
    }

    // ===== deleteByMemberId =====

    @Test
    @DisplayName("deleteByMemberId 호출 시 Repository의 삭제 메서드를 호출한다")
    fun deleteByMemberId_callsRepository() {
        refreshTokenService.deleteByMemberId(1L)

        verify(refreshTokenRepository).deleteByMemberId(1L)
    }

    @Test
    @DisplayName("이미 삭제된 Refresh Token에 다시 deleteByMemberId를 호출해도 예외 없이 통과한다(멱등)")
    fun deleteByMemberId_calledTwice_bothSucceed() {
        refreshTokenService.deleteByMemberId(1L)
        refreshTokenService.deleteByMemberId(1L)

        verify(refreshTokenRepository, times(2)).deleteByMemberId(1L)
    }

    @Test
    @DisplayName("저장소 삭제 중 예외가 발생해도(Redis 장애 등) 예외를 던지지 않는다 — 로그아웃은 fail-closed가 아니다")
    fun deleteByMemberId_repositoryThrows_doesNotPropagate() {
        doThrow(RedisConnectionFailureException("connection refused"))
            .`when`(refreshTokenRepository)
            .deleteByMemberId(1L)

        assertThatCode { refreshTokenService.deleteByMemberId(1L) }.doesNotThrowAnyException()
    }
}
