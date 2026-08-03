package com.dongnemarket.auth.service

import com.dongnemarket.auth.client.OAuthAuthorizationUrlFactory
import com.dongnemarket.auth.client.OAuthClient
import com.dongnemarket.auth.client.OAuthUserIdentity
import com.dongnemarket.auth.dto.LoginRequest
import com.dongnemarket.auth.dto.SignupRequest
import com.dongnemarket.auth.entity.MemberSocialAccount
import com.dongnemarket.auth.entity.OAuthProvider
import com.dongnemarket.auth.entity.RefreshToken
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.auth.repository.InMemoryLoginAttemptRepository
import com.dongnemarket.auth.repository.MemberSocialAccountRepository
import com.dongnemarket.auth.repository.OAuthAuthorizationState
import com.dongnemarket.auth.repository.OAuthStateRepository
import com.dongnemarket.auth.repository.RefreshTokenRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.member.entity.AgreementType
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberAgreement
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberAgreementRepository
import com.dongnemarket.member.repository.MemberRepository
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
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional

/**
 * Repository만 Mock 처리하고, 외부 시스템 의존성이 없는 PasswordEncoder·JwtTokenProvider·RefreshTokenService는
 * 실제 구현체를 사용해 AuthService의 비즈니스 흐름을 검증한다.
 */
@ExtendWith(MockitoExtension::class)
class AuthServiceTest {
    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var refreshTokenRepository: RefreshTokenRepository

    @Mock
    lateinit var emailVerificationRepository: EmailVerificationRepository

    @Mock
    lateinit var memberAgreementRepository: MemberAgreementRepository

    @Mock
    lateinit var oauthStateRepository: OAuthStateRepository

    @Mock
    lateinit var memberSocialAccountRepository: MemberSocialAccountRepository

    @Mock
    lateinit var oauthSignupTransaction: OAuthSignupTransaction

    @Mock
    lateinit var oauthClient: OAuthClient

    @Mock
    lateinit var oauthAuthorizationUrlFactory: OAuthAuthorizationUrlFactory

    lateinit var passwordEncoder: PasswordEncoder
    lateinit var jwtTokenProvider: JwtTokenProvider
    lateinit var refreshTokenService: RefreshTokenService
    lateinit var loginAttemptService: LoginAttemptService
    lateinit var authService: AuthService

    @BeforeEach
    fun setUp() {
        passwordEncoder = BCryptPasswordEncoder()
        jwtTokenProvider =
            JwtTokenProvider(
                "test-jwt-secret-key-for-auth-service-unit-test-0123456789",
                3600L,
                604800L,
            )
        refreshTokenService = RefreshTokenService(refreshTokenRepository, jwtTokenProvider)
        loginAttemptService = LoginAttemptService(InMemoryLoginAttemptRepository())
        authService =
            AuthService(
                memberRepository,
                passwordEncoder,
                jwtTokenProvider,
                refreshTokenService,
                emailVerificationRepository,
                memberAgreementRepository,
                loginAttemptService,
                oauthStateRepository,
                memberSocialAccountRepository,
                oauthSignupTransaction,
                oauthAuthorizationUrlFactory,
                300L,
                5,
            )
    }

    // ===== signup =====

    @Test
    @DisplayName("이메일·닉네임이 중복되지 않고 이메일 인증이 완료됐으면 회원가입에 성공한다")
    fun signup_success() {
        val request = SignupRequest("test@example.com", "password123", "tester", true, true)
        given(memberRepository.existsByEmail(request.email)).willReturn(false)
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue(request.email)).willReturn(true)
        given(memberRepository.existsByNickname(request.nickname)).willReturn(false)
        given(memberRepository.save(any(Member::class.java))).willAnswer { it.getArgument<Member>(0) }

        val response = authService.signup(request, TEST_IP, TEST_USER_AGENT)

        assertThat(response.email).isEqualTo(request.email)
        assertThat(response.nickname).isEqualTo(request.nickname)
    }

    @Test
    @DisplayName("이용약관에 동의하지 않으면 TERMS_NOT_AGREED 예외가 발생하고 회원가입이 진행되지 않는다")
    fun signup_termsNotAgreed_throwsException() {
        val request = SignupRequest("test@example.com", "password123", "tester", false, true)

        assertThatThrownBy { authService.signup(request, TEST_IP, TEST_USER_AGENT) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TERMS_NOT_AGREED)

        verify(memberRepository, never()).save(any())
        verify(memberAgreementRepository, never()).save(any())
    }

    @Test
    @DisplayName("개인정보 수집 및 이용에 동의하지 않으면 PERSONAL_INFO_COLLECTION_NOT_AGREED 예외가 발생하고 회원가입이 진행되지 않는다")
    fun signup_personalInfoCollectionNotAgreed_throwsException() {
        val request = SignupRequest("test@example.com", "password123", "tester", true, false)

        assertThatThrownBy { authService.signup(request, TEST_IP, TEST_USER_AGENT) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERSONAL_INFO_COLLECTION_NOT_AGREED)

        verify(memberRepository, never()).save(any())
        verify(memberAgreementRepository, never()).save(any())
    }

    @Test
    @DisplayName("이용약관·개인정보 모두 동의하면 회원가입 성공과 함께 MemberAgreement가 항목별로 1건씩(총 2건) 저장된다")
    fun signup_bothAgreed_savesTwoMemberAgreements() {
        val request = SignupRequest("test@example.com", "password123", "tester", true, true)
        given(memberRepository.existsByEmail(request.email)).willReturn(false)
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue(request.email)).willReturn(true)
        given(memberRepository.existsByNickname(request.nickname)).willReturn(false)
        given(memberRepository.save(any(Member::class.java))).willAnswer { it.getArgument<Member>(0) }

        authService.signup(request, TEST_IP, TEST_USER_AGENT)

        val captor: ArgumentCaptor<MemberAgreement> = ArgumentCaptor.forClass(MemberAgreement::class.java)
        verify(memberAgreementRepository, times(2)).save(captor.capture())
        assertThat(captor.allValues)
            .extracting<AgreementType>(MemberAgreement::getAgreementType)
            .containsExactlyInAnyOrder(AgreementType.TERMS_OF_SERVICE, AgreementType.PERSONAL_INFO_COLLECTION)
        assertThat(captor.allValues)
            .allSatisfy { agreement ->
                assertThat(agreement.version).isEqualTo("v1.0")
                assertThat(agreement.agreedAt).isNotNull()
                assertThat(agreement.ipAddress).isEqualTo(TEST_IP)
                assertThat(agreement.userAgent).isEqualTo(TEST_USER_AGENT)
            }
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 DUPLICATE_EMAIL 예외가 발생한다")
    fun signup_duplicateEmail_throwsException() {
        val request = SignupRequest("test@example.com", "password123", "tester", true, true)
        given(memberRepository.existsByEmail(request.email)).willReturn(true)

        assertThatThrownBy { authService.signup(request, TEST_IP, TEST_USER_AGENT) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_EMAIL)

        verify(memberRepository, never()).save(any())
    }

    @Test
    @DisplayName("이메일 인증을 완료하지 않았으면 EMAIL_NOT_VERIFIED 예외가 발생한다")
    fun signup_emailNotVerified_throwsException() {
        val request = SignupRequest("unverified@example.com", "password123", "unverifiedUser", true, true)
        given(memberRepository.existsByEmail(request.email)).willReturn(false)
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue(request.email)).willReturn(false)

        assertThatThrownBy { authService.signup(request, TEST_IP, TEST_USER_AGENT) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_NOT_VERIFIED)

        verify(memberRepository, never()).save(any())
    }

    @Test
    @DisplayName("이메일 인증 요청 이력 자체가 없으면 EMAIL_NOT_VERIFIED 예외가 발생한다")
    fun signup_noVerificationHistory_throwsException() {
        val request = SignupRequest("never-requested@example.com", "password123", "neverRequestedUser", true, true)
        given(memberRepository.existsByEmail(request.email)).willReturn(false)
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue(request.email)).willReturn(false)

        assertThatThrownBy { authService.signup(request, TEST_IP, TEST_USER_AGENT) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_NOT_VERIFIED)

        verify(memberRepository, never()).save(any())
    }

    @Test
    @DisplayName("이미 사용 중인 닉네임이면 DUPLICATE_NICKNAME 예외가 발생한다")
    fun signup_duplicateNickname_throwsException() {
        val request = SignupRequest("test@example.com", "password123", "tester", true, true)
        given(memberRepository.existsByEmail(request.email)).willReturn(false)
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue(request.email)).willReturn(true)
        given(memberRepository.existsByNickname(request.nickname)).willReturn(true)

        assertThatThrownBy { authService.signup(request, TEST_IP, TEST_USER_AGENT) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_NICKNAME)

        verify(memberRepository, never()).save(any())
    }

    @Test
    @DisplayName("중복 체크 통과 후 save() 시점에 이메일 unique 제약을 위반하면(race condition) DUPLICATE_EMAIL로 변환한다")
    fun signup_raceConditionDuplicateEmail_throwsDuplicateEmail() {
        val request = SignupRequest("race@example.com", "password123", "racer", true, true)
        given(memberRepository.existsByEmail(request.email)).willReturn(false, true)
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue(request.email)).willReturn(true)
        given(memberRepository.existsByNickname(request.nickname)).willReturn(false)
        given(memberRepository.save(any(Member::class.java))).willThrow(DataIntegrityViolationException("duplicate entry"))

        assertThatThrownBy { authService.signup(request, TEST_IP, TEST_USER_AGENT) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_EMAIL)
    }

    @Test
    @DisplayName("중복 체크 통과 후 save() 시점에 닉네임 unique 제약을 위반하면(race condition) DUPLICATE_NICKNAME으로 변환한다")
    fun signup_raceConditionDuplicateNickname_throwsDuplicateNickname() {
        val request = SignupRequest("racer2@example.com", "password123", "raceNick", true, true)
        given(memberRepository.existsByEmail(request.email)).willReturn(false)
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue(request.email)).willReturn(true)
        given(memberRepository.existsByNickname(request.nickname)).willReturn(false, true)
        given(memberRepository.save(any(Member::class.java))).willThrow(DataIntegrityViolationException("duplicate entry"))

        assertThatThrownBy { authService.signup(request, TEST_IP, TEST_USER_AGENT) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_NICKNAME)
    }

    @Test
    @DisplayName("원인을 식별할 수 없는 무결성 제약 위반은 원본 예외를 그대로 던진다")
    fun signup_unclassifiableIntegrityViolation_rethrowsOriginal() {
        val request = SignupRequest("unknown@example.com", "password123", "unknown", true, true)
        given(memberRepository.existsByEmail(request.email)).willReturn(false)
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue(request.email)).willReturn(true)
        given(memberRepository.existsByNickname(request.nickname)).willReturn(false)
        val original = DataIntegrityViolationException("unknown constraint")
        given(memberRepository.save(any(Member::class.java))).willThrow(original)

        assertThatThrownBy { authService.signup(request, TEST_IP, TEST_USER_AGENT) }
            .isSameAs(original)
    }

    // ===== login =====

    @Test
    @DisplayName("올바른 이메일·비밀번호로 로그인하면 memberId가 담긴 accessToken·refreshToken을 반환한다")
    fun login_success() {
        val request = LoginRequest("test@example.com", "password123")
        val member = Member.createUser(request.email, passwordEncoder.encode(request.password), "tester")
        ReflectionTestUtils.setField(member, "id", 1L)
        given(memberRepository.findByEmail(request.email)).willReturn(Optional.of(member))

        val response = authService.login(request)

        assertThat(response.accessToken).isNotBlank()
        assertThat(response.refreshToken).isNotBlank()
        assertThat(jwtTokenProvider.getMemberId(response.accessToken!!)).isEqualTo(1L)
        assertThat(jwtTokenProvider.getMemberId(response.refreshToken!!)).isEqualTo(1L)
        verify(refreshTokenRepository).save(any(RefreshToken::class.java))
    }

    @Test
    @DisplayName("이미 Refresh Token이 저장된 회원이 재로그인하면 새 토큰으로 저장소에 다시 save()한다")
    fun login_existingRefreshToken_savesNewToken() {
        val request = LoginRequest("test@example.com", "password123")
        val member = Member.createUser(request.email, passwordEncoder.encode(request.password), "tester")
        ReflectionTestUtils.setField(member, "id", 1L)
        given(memberRepository.findByEmail(request.email)).willReturn(Optional.of(member))

        val response = authService.login(request)

        val captor: ArgumentCaptor<RefreshToken> = ArgumentCaptor.forClass(RefreshToken::class.java)
        verify(refreshTokenRepository).save(captor.capture())
        assertThat(captor.value.memberId).isEqualTo(1L)
        assertThat(captor.value.token).isEqualTo(response.refreshToken)
    }

    // ===== reissue =====

    @Test
    @DisplayName("유효한 Refresh Token으로 재발급하면 새 accessToken과 함께 회전된(기존과 다른) 새 refreshToken을 반환하고 저장소에 반영한다")
    fun reissue_success() {
        val refreshToken = jwtTokenProvider.createRefreshToken(1L)
        val member = Member.createUser("test@example.com", "encoded", "tester")
        ReflectionTestUtils.setField(member, "id", 1L)
        given(refreshTokenRepository.findByMemberId(1L))
            .willReturn(Optional.of(RefreshToken.issue(1L, refreshToken, LocalDateTime.now().plusDays(7))))
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))

        val response = authService.reissue(refreshToken)

        // JWT는 초 단위 iat/exp라 같은 초 안에 발급하면 문자열이 우연히 같아질 수 있어(회전 자체는 항상 일어남),
        // "다른 문자열"이 아니라 "저장소에 다시 save()됐는지"로 회전 여부를 검증한다.
        assertThat(jwtTokenProvider.getMemberId(response.accessToken!!)).isEqualTo(1L)
        assertThat(jwtTokenProvider.getMemberId(response.refreshToken!!)).isEqualTo(1L)
        val captor: ArgumentCaptor<RefreshToken> = ArgumentCaptor.forClass(RefreshToken::class.java)
        verify(refreshTokenRepository).save(captor.capture())
        assertThat(captor.value.token).isEqualTo(response.refreshToken)
    }

    @Test
    @DisplayName("만료된 Refresh Token으로 재발급하면 EXPIRED_REFRESH_TOKEN 예외가 발생한다")
    fun reissue_expiredRefreshToken_throwsException() {
        val shortLivedProvider =
            JwtTokenProvider(
                "test-jwt-secret-key-for-auth-service-unit-test-0123456789",
                3600L,
                0L,
            )
        val expiredToken = shortLivedProvider.createRefreshToken(1L)

        assertThatThrownBy { authService.reissue(expiredToken) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXPIRED_REFRESH_TOKEN)
    }

    @Test
    @DisplayName("다른 키로 서명된(위조) Refresh Token으로 재발급하면 INVALID_REFRESH_TOKEN 예외가 발생한다")
    fun reissue_forgedSignature_throwsInvalidRefreshToken() {
        val otherProvider =
            JwtTokenProvider(
                "a-totally-different-secret-key-for-forgery-0123456789",
                3600L,
                604800L,
            )
        val forgedToken = otherProvider.createRefreshToken(1L)

        assertThatThrownBy { authService.reissue(forgedToken) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN)
    }

    @Test
    @DisplayName("DB에 저장된 Refresh Token이 없으면 REFRESH_TOKEN_NOT_FOUND 예외가 발생한다")
    fun reissue_notFoundInDb_throwsRefreshTokenNotFound() {
        val refreshToken = jwtTokenProvider.createRefreshToken(1L)
        given(refreshTokenRepository.findByMemberId(1L)).willReturn(Optional.empty())

        assertThatThrownBy { authService.reissue(refreshToken) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFRESH_TOKEN_NOT_FOUND)
    }

    @Test
    @DisplayName("DB에 저장된 값과 다른 Refresh Token으로 재발급하면 INVALID_REFRESH_TOKEN 예외가 발생한다")
    fun reissue_tokenMismatch_throwsInvalidRefreshToken() {
        val refreshToken = jwtTokenProvider.createRefreshToken(1L)
        given(refreshTokenRepository.findByMemberId(1L))
            .willReturn(Optional.of(RefreshToken.issue(1L, "different-stored-token", LocalDateTime.now().plusDays(7))))

        assertThatThrownBy { authService.reissue(refreshToken) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN)
    }

    @Test
    @DisplayName("Access Token으로 재발급을 시도하면 INVALID_REFRESH_TOKEN 예외가 발생한다")
    fun reissue_accessTokenPresented_throwsInvalidRefreshToken() {
        val accessToken = jwtTokenProvider.createAccessToken(1L, "ROLE_USER")

        assertThatThrownBy { authService.reissue(accessToken) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN)
    }

    @Test
    @DisplayName("탈퇴한 회원이 Refresh Token으로 재발급을 시도하면 DELETED_MEMBER 예외가 발생한다")
    fun reissue_deletedMember_throwsException() {
        val refreshToken = jwtTokenProvider.createRefreshToken(1L)
        val member = Member.createUser("test@example.com", "encoded", "tester")
        ReflectionTestUtils.setField(member, "id", 1L)
        member.changeStatus(MemberStatus.DELETED)
        given(refreshTokenRepository.findByMemberId(1L))
            .willReturn(Optional.of(RefreshToken.issue(1L, refreshToken, LocalDateTime.now().plusDays(7))))
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))

        assertThatThrownBy { authService.reissue(refreshToken) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DELETED_MEMBER)
    }

    @Test
    @DisplayName("정지된 회원이 Refresh Token으로 재발급을 시도하면 SUSPENDED_MEMBER 예외가 발생한다")
    fun reissue_suspendedMember_throwsException() {
        val refreshToken = jwtTokenProvider.createRefreshToken(1L)
        val member = Member.createUser("test@example.com", "encoded", "tester")
        ReflectionTestUtils.setField(member, "id", 1L)
        member.changeStatus(MemberStatus.SUSPENDED)
        given(refreshTokenRepository.findByMemberId(1L))
            .willReturn(Optional.of(RefreshToken.issue(1L, refreshToken, LocalDateTime.now().plusDays(7))))
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))

        assertThatThrownBy { authService.reissue(refreshToken) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUSPENDED_MEMBER)
    }

    // ===== logout =====

    @Test
    @DisplayName("로그아웃하면 저장된 Refresh Token이 삭제된다")
    fun logout_callsRefreshTokenServiceDeleteByMemberId() {
        authService.logout(1L)

        verify(refreshTokenRepository).deleteByMemberId(1L)
    }

    @Test
    @DisplayName("로그아웃을 여러 번 호출해도 항상 성공한다(멱등)")
    fun logout_calledTwice_bothSucceedWithoutException() {
        authService.logout(1L)

        assertThatCode { authService.logout(1L) }.doesNotThrowAnyException()
    }

    @Test
    @DisplayName("로그아웃 이후 기존 Refresh Token으로 재발급을 시도하면 REFRESH_TOKEN_NOT_FOUND 예외가 발생한다")
    fun reissue_afterLogout_throwsRefreshTokenNotFound() {
        val request = LoginRequest("test@example.com", "password123")
        val member = Member.createUser(request.email, passwordEncoder.encode(request.password), "tester")
        ReflectionTestUtils.setField(member, "id", 1L)
        given(memberRepository.findByEmail(request.email)).willReturn(Optional.of(member))
        // 로그인 시점에는 저장된 row가 없어 신규 저장되고, 로그아웃(삭제) 이후 재발급 시점에도 row가 없는 상태를 그대로 재현한다.
        given(refreshTokenRepository.findByMemberId(1L)).willReturn(Optional.empty())

        val loginResponse = authService.login(request)
        val refreshToken = loginResponse.refreshToken

        authService.logout(1L)

        assertThatThrownBy { authService.reissue(refreshToken) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFRESH_TOKEN_NOT_FOUND)
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인하면 MEMBER_NOT_FOUND 예외가 발생한다")
    fun login_emailNotFound_throwsException() {
        val request = LoginRequest("none@example.com", "password123")
        given(memberRepository.findByEmail(request.email)).willReturn(Optional.empty())

        assertThatThrownBy { authService.login(request) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND)
    }

    @Test
    @DisplayName("비밀번호가 일치하지 않으면 INVALID_PASSWORD 예외가 발생한다")
    fun login_wrongPassword_throwsException() {
        val request = LoginRequest("test@example.com", "wrongPassword")
        val member = Member.createUser(request.email, passwordEncoder.encode("password123"), "tester")
        given(memberRepository.findByEmail(request.email)).willReturn(Optional.of(member))

        assertThatThrownBy { authService.login(request) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD)
    }

    @Test
    @DisplayName("소셜 로그인 전용 회원이 비밀번호 로그인을 시도하면 존재하지 않는 이메일과 동일하게 MEMBER_NOT_FOUND 예외가 발생한다(INVALID_PASSWORD로 새지 않음)")
    fun login_socialOnlyAccount_throwsMemberNotFoundLikeUnknownEmail() {
        val request = LoginRequest("social@example.com", "anyPassword123!")
        val member = Member.createSocialUser(request.email, "dummy-encoded-hash", "kakao_loginguard1")
        given(memberRepository.findByEmail(request.email)).willReturn(Optional.of(member))

        assertThatThrownBy { authService.login(request) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND)
    }

    @Test
    @DisplayName("탈퇴한 회원이 로그인하면 DELETED_MEMBER 예외가 발생한다")
    fun login_deletedMember_throwsException() {
        val request = LoginRequest("deleted@example.com", "password123")
        val member = Member.createUser(request.email, passwordEncoder.encode(request.password), "tester")
        member.changeStatus(MemberStatus.DELETED)
        given(memberRepository.findByEmail(request.email)).willReturn(Optional.of(member))

        assertThatThrownBy { authService.login(request) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DELETED_MEMBER)
    }

    @Test
    @DisplayName("정지된 회원이 로그인하면 SUSPENDED_MEMBER 예외가 발생한다")
    fun login_suspendedMember_throwsException() {
        val request = LoginRequest("suspended@example.com", "password123")
        val member = Member.createUser(request.email, passwordEncoder.encode(request.password), "tester")
        member.changeStatus(MemberStatus.SUSPENDED)
        given(memberRepository.findByEmail(request.email)).willReturn(Optional.of(member))

        assertThatThrownBy { authService.login(request) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUSPENDED_MEMBER)
    }

    // ===== login 실패 횟수 제한 =====

    @Test
    @DisplayName("비밀번호를 4번 틀려도 아직 차단되지 않고 매번 INVALID_PASSWORD 예외가 발생한다")
    fun login_wrongPasswordFourTimes_stillNotBlocked() {
        val request = LoginRequest("lockout@example.com", "wrongPassword")
        val member = Member.createUser(request.email, passwordEncoder.encode("password123"), "tester")
        given(memberRepository.findByEmail(request.email)).willReturn(Optional.of(member))

        repeat(4) {
            assertThatThrownBy { authService.login(request) }
                .isInstanceOf(BusinessException::class.java)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD)
        }
    }

    @Test
    @DisplayName("비밀번호를 5번 틀리면 그 다음 로그인 시도는 자격증명 확인 전에 TOO_MANY_LOGIN_ATTEMPTS 예외가 발생한다")
    fun login_wrongPasswordFiveTimes_thenBlocksNextAttempt() {
        val request = LoginRequest("lockout2@example.com", "wrongPassword")
        val member = Member.createUser(request.email, passwordEncoder.encode("password123"), "tester")
        given(memberRepository.findByEmail(request.email)).willReturn(Optional.of(member))

        repeat(5) {
            assertThatThrownBy { authService.login(request) }
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD)
        }

        assertThatThrownBy { authService.login(request) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOO_MANY_LOGIN_ATTEMPTS)
        // 차단 상태에서는 자격증명을 다시 확인하지 않는다.
        verify(memberRepository, times(5)).findByEmail(request.email)
    }

    @Test
    @DisplayName("로그인에 성공하면 이전 실패 횟수가 초기화되어, 이후 실패는 다시 1회부터 카운트된다")
    fun login_success_resetsFailureCount() {
        val email = "lockout3@example.com"
        val member = Member.createUser(email, passwordEncoder.encode("password123"), "tester")
        given(memberRepository.findByEmail(email)).willReturn(Optional.of(member))

        val wrongRequest = LoginRequest(email, "wrongPassword")
        repeat(4) {
            assertThatThrownBy { authService.login(wrongRequest) }
        }

        val correctRequest = LoginRequest(email, "password123")
        assertThatCode { authService.login(correctRequest) }.doesNotThrowAnyException()

        // 성공 이후 다시 실패해도(1회) 아직 차단되지 않는다 — 카운트가 리셋됐다는 뜻.
        assertThatThrownBy { authService.login(wrongRequest) }
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD)
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 5번 시도해도 실패 횟수에 반영되어 차단된다")
    fun login_memberNotFoundFiveTimes_thenBlocksNextAttempt() {
        val request = LoginRequest("neverexisted@example.com", "password123")
        given(memberRepository.findByEmail(request.email)).willReturn(Optional.empty())

        repeat(5) {
            assertThatThrownBy { authService.login(request) }
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND)
        }

        assertThatThrownBy { authService.login(request) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOO_MANY_LOGIN_ATTEMPTS)
    }

    // ===== oauthLogin =====

    private fun sampleAuthState(): OAuthAuthorizationState =
        OAuthAuthorizationState(
            OAuthProvider.KAKAO,
            BROWSER_CORRELATION_HASH,
            "https://app.example.com/callback",
            "code-verifier",
            null,
            Instant.now(),
        )

    private fun sampleIdentity(email: String): OAuthUserIdentity = OAuthUserIdentity(OAuthProvider.KAKAO, "provider-user-1", email)

    @Test
    @DisplayName("state가 유효하지 않으면(만료·재사용·불일치) INVALID_OAUTH_STATE 예외가 발생하고 제공자 호출은 일어나지 않는다")
    fun oauthLogin_invalidState_throwsAndNeverCallsProvider() {
        given(oauthClient.provider()).willReturn(OAuthProvider.KAKAO)
        given(oauthStateRepository.consume(STATE, OAuthProvider.KAKAO, BROWSER_CORRELATION_HASH))
            .willReturn(Optional.empty())

        assertThatThrownBy { authService.oauthLogin(oauthClient, CODE, STATE, BROWSER_CORRELATION_HASH) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OAUTH_STATE)

        verify(oauthClient, never()).resolveIdentity(any(), any(), any(), any())
    }

    @Test
    @DisplayName("이미 연동된 소셜 계정으로 로그인하면 신규가입 없이 바로 토큰을 발급한다")
    fun oauthLogin_existingLinkedMember_issuesTokensWithoutSignup() {
        val member = Member.createSocialUser("existing@kakao.com", "dummy-hash", "kakao_existing")
        ReflectionTestUtils.setField(member, "id", 10L)
        given(oauthClient.provider()).willReturn(OAuthProvider.KAKAO)
        given(oauthStateRepository.consume(STATE, OAuthProvider.KAKAO, BROWSER_CORRELATION_HASH))
            .willReturn(Optional.of(sampleAuthState()))
        given(oauthClient.resolveIdentity(CODE, "code-verifier", "https://app.example.com/callback", null))
            .willReturn(sampleIdentity("existing@kakao.com"))
        given(memberSocialAccountRepository.findByProviderAndProviderUserIdFetchMember(OAuthProvider.KAKAO, "provider-user-1"))
            .willReturn(Optional.of(MemberSocialAccount.of(member, OAuthProvider.KAKAO, "provider-user-1")))

        val response = authService.oauthLogin(oauthClient, CODE, STATE, BROWSER_CORRELATION_HASH)

        assertThat(jwtTokenProvider.getMemberId(response.accessToken!!)).isEqualTo(10L)
        verify(oauthSignupTransaction, never()).signUp(any())
    }

    @Test
    @DisplayName("처음 보는 소셜 계정이고 이메일도 겹치지 않으면 신규가입 후 토큰을 발급한다")
    fun oauthLogin_newIdentity_signsUpAndIssuesTokens() {
        val newMember = Member.createSocialUser("new@kakao.com", "dummy-hash", "kakao_newbie")
        ReflectionTestUtils.setField(newMember, "id", 11L)
        given(oauthClient.provider()).willReturn(OAuthProvider.KAKAO)
        given(oauthStateRepository.consume(STATE, OAuthProvider.KAKAO, BROWSER_CORRELATION_HASH))
            .willReturn(Optional.of(sampleAuthState()))
        given(oauthClient.resolveIdentity(CODE, "code-verifier", "https://app.example.com/callback", null))
            .willReturn(sampleIdentity("new@kakao.com"))
        given(memberSocialAccountRepository.findByProviderAndProviderUserIdFetchMember(OAuthProvider.KAKAO, "provider-user-1"))
            .willReturn(Optional.empty())
        given(memberRepository.existsByEmail("new@kakao.com")).willReturn(false)
        given(oauthSignupTransaction.signUp(any(OAuthUserIdentity::class.java))).willReturn(newMember)

        val response = authService.oauthLogin(oauthClient, CODE, STATE, BROWSER_CORRELATION_HASH)

        assertThat(jwtTokenProvider.getMemberId(response.accessToken!!)).isEqualTo(11L)
    }

    @Test
    @DisplayName("이메일이 이미 다른 계정(로컬 또는 다른 provider)에 쓰이고 있으면 신규가입을 시도하지 않고 OAUTH_EMAIL_CONFLICT를 던진다")
    fun oauthLogin_emailAlreadyUsed_throwsConflictWithoutAttemptingSignup() {
        given(oauthClient.provider()).willReturn(OAuthProvider.KAKAO)
        given(oauthStateRepository.consume(STATE, OAuthProvider.KAKAO, BROWSER_CORRELATION_HASH))
            .willReturn(Optional.of(sampleAuthState()))
        given(oauthClient.resolveIdentity(CODE, "code-verifier", "https://app.example.com/callback", null))
            .willReturn(sampleIdentity("taken@example.com"))
        given(memberSocialAccountRepository.findByProviderAndProviderUserIdFetchMember(OAuthProvider.KAKAO, "provider-user-1"))
            .willReturn(Optional.empty())
        given(memberRepository.existsByEmail("taken@example.com")).willReturn(true)

        assertThatThrownBy { authService.oauthLogin(oauthClient, CODE, STATE, BROWSER_CORRELATION_HASH) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OAUTH_EMAIL_CONFLICT)

        verify(oauthSignupTransaction, never()).signUp(any())
    }

    @Test
    @DisplayName("동시 최초 로그인 레이스로 signUp이 UNIQUE 위반이 나도, 재조회에서 동일 연동을 찾으면 그 회원으로 로그인을 이어간다")
    fun oauthLogin_raceConditionDuringSignup_reconciledMemberFound_succeeds() {
        val concurrentlyCreated = Member.createSocialUser("race@kakao.com", "dummy-hash", "kakao_race")
        ReflectionTestUtils.setField(concurrentlyCreated, "id", 12L)
        given(oauthClient.provider()).willReturn(OAuthProvider.KAKAO)
        given(oauthStateRepository.consume(STATE, OAuthProvider.KAKAO, BROWSER_CORRELATION_HASH))
            .willReturn(Optional.of(sampleAuthState()))
        given(oauthClient.resolveIdentity(CODE, "code-verifier", "https://app.example.com/callback", null))
            .willReturn(sampleIdentity("race@kakao.com"))
        given(memberSocialAccountRepository.findByProviderAndProviderUserIdFetchMember(OAuthProvider.KAKAO, "provider-user-1"))
            .willReturn(Optional.empty())
        given(memberRepository.existsByEmail("race@kakao.com")).willReturn(false)
        given(oauthSignupTransaction.signUp(any(OAuthUserIdentity::class.java)))
            .willThrow(DataIntegrityViolationException("duplicate entry"))
        given(oauthSignupTransaction.reconcileAfterConflict(OAuthProvider.KAKAO, "provider-user-1"))
            .willReturn(Optional.of(concurrentlyCreated))

        val response = authService.oauthLogin(oauthClient, CODE, STATE, BROWSER_CORRELATION_HASH)

        assertThat(jwtTokenProvider.getMemberId(response.accessToken!!)).isEqualTo(12L)
    }

    @Test
    @DisplayName("signUp이 UNIQUE 위반이 났는데 재조회에서도 동일 연동을 못 찾으면(다른 제약 충돌) OAUTH_EMAIL_CONFLICT를 던진다")
    fun oauthLogin_raceConditionDuringSignup_reconcileFindsNothing_throwsConflict() {
        given(oauthClient.provider()).willReturn(OAuthProvider.KAKAO)
        given(oauthStateRepository.consume(STATE, OAuthProvider.KAKAO, BROWSER_CORRELATION_HASH))
            .willReturn(Optional.of(sampleAuthState()))
        given(oauthClient.resolveIdentity(CODE, "code-verifier", "https://app.example.com/callback", null))
            .willReturn(sampleIdentity("conflict@kakao.com"))
        given(memberSocialAccountRepository.findByProviderAndProviderUserIdFetchMember(OAuthProvider.KAKAO, "provider-user-1"))
            .willReturn(Optional.empty())
        given(memberRepository.existsByEmail("conflict@kakao.com")).willReturn(false)
        given(oauthSignupTransaction.signUp(any(OAuthUserIdentity::class.java)))
            .willThrow(DataIntegrityViolationException("duplicate entry"))
        given(oauthSignupTransaction.reconcileAfterConflict(OAuthProvider.KAKAO, "provider-user-1"))
            .willReturn(Optional.empty())

        assertThatThrownBy { authService.oauthLogin(oauthClient, CODE, STATE, BROWSER_CORRELATION_HASH) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OAUTH_EMAIL_CONFLICT)
    }

    @Test
    @DisplayName("탈퇴한 회원과 연동된 소셜 계정으로 로그인하면 DELETED_MEMBER 예외가 발생한다")
    fun oauthLogin_deletedLinkedMember_throwsException() {
        val member = Member.createSocialUser("deleted@kakao.com", "dummy-hash", "kakao_deleted")
        ReflectionTestUtils.setField(member, "id", 13L)
        member.changeStatus(MemberStatus.DELETED)
        given(oauthClient.provider()).willReturn(OAuthProvider.KAKAO)
        given(oauthStateRepository.consume(STATE, OAuthProvider.KAKAO, BROWSER_CORRELATION_HASH))
            .willReturn(Optional.of(sampleAuthState()))
        given(oauthClient.resolveIdentity(CODE, "code-verifier", "https://app.example.com/callback", null))
            .willReturn(sampleIdentity("deleted@kakao.com"))
        given(memberSocialAccountRepository.findByProviderAndProviderUserIdFetchMember(OAuthProvider.KAKAO, "provider-user-1"))
            .willReturn(Optional.of(MemberSocialAccount.of(member, OAuthProvider.KAKAO, "provider-user-1")))

        assertThatThrownBy { authService.oauthLogin(oauthClient, CODE, STATE, BROWSER_CORRELATION_HASH) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DELETED_MEMBER)
    }

    companion object {
        private const val TEST_IP = "127.0.0.1"
        private const val TEST_USER_AGENT = "JUnit-Test-Agent"

        private const val STATE = "state-value"
        private const val BROWSER_CORRELATION_HASH = "bcid-hash"
        private const val CODE = "auth-code"
    }
}
