package com.dongnemarket.auth.service

import com.dongnemarket.auth.client.OAuthAuthorizationUrlFactory
import com.dongnemarket.auth.client.OAuthClient
import com.dongnemarket.auth.client.OAuthUserIdentity
import com.dongnemarket.auth.dto.LoginRequest
import com.dongnemarket.auth.dto.SignupRequest
import com.dongnemarket.auth.entity.MemberSocialAccount
import com.dongnemarket.auth.entity.OAuthProvider
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.auth.repository.MemberSocialAccountRepository
import com.dongnemarket.auth.repository.OAuthAuthorizationState
import com.dongnemarket.auth.repository.OAuthStateRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberAgreement
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.entity.Role
import com.dongnemarket.member.repository.MemberAgreementRepository
import com.dongnemarket.member.repository.MemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * [AuthService] 의 **협력자 호출 순서 계약**을 고정한다. 기존 `AuthServiceTest`(Java)는 결과(반환값·예외)를
 * 검증하므로, 여기서는 순서가 뒤바뀌어도 결과가 같아 조용히 어긋날 수 있는 것들만 다룬다.
 *
 * - signup: 약관 → 이메일 중복 → 이메일 인증 → 닉네임 중복 → encode → save → 동의이력 2건 순서
 * - login: 차단 확인이 회원 조회보다 **먼저**(차단 시 자격증명 확인 자체가 없어야 한다),
 *   소셜 전용 회원은 BCrypt 호출 없이 합류(타이밍 신호 차단), 상태 거부는 실패 카운트에 미반영
 * - startAuthorization: 저장(issue)에 성공해야 인가 URL 을 만들고, nonce 는 구글만 발급되며
 *   저장값과 인가 URL 에 같은 값이 들어간다
 * - oauthLogin: **state 소비가 제공자 호출보다 먼저**(실패한 제공자 호출이 state 를 남기면 재사용 가능),
 *   소비된 state 의 codeVerifier·redirectUri·oidcNonce 가 그대로 제공자 호출에 전달된다
 * - reissue: 검증 → 회원 조회 → 새 토큰 저장 순서
 */
class AuthCoreServiceOrchestrationContractTest {
    private lateinit var memberRepository: MemberRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var refreshTokenService: RefreshTokenService
    private lateinit var emailVerificationRepository: EmailVerificationRepository
    private lateinit var memberAgreementRepository: MemberAgreementRepository
    private lateinit var loginAttemptService: LoginAttemptService
    private lateinit var oauthStateRepository: OAuthStateRepository
    private lateinit var memberSocialAccountRepository: MemberSocialAccountRepository
    private lateinit var oauthSignupTransaction: OAuthSignupTransaction
    private lateinit var oauthAuthorizationUrlFactory: OAuthAuthorizationUrlFactory
    private lateinit var authService: AuthService

    private lateinit var member: Member

    @BeforeEach
    fun setUp() {
        memberRepository = mock(MemberRepository::class.java)
        passwordEncoder = mock(PasswordEncoder::class.java)
        jwtTokenProvider = mock(JwtTokenProvider::class.java)
        refreshTokenService = mock(RefreshTokenService::class.java)
        emailVerificationRepository = mock(EmailVerificationRepository::class.java)
        memberAgreementRepository = mock(MemberAgreementRepository::class.java)
        loginAttemptService = mock(LoginAttemptService::class.java)
        oauthStateRepository = mock(OAuthStateRepository::class.java)
        memberSocialAccountRepository = mock(MemberSocialAccountRepository::class.java)
        oauthSignupTransaction = mock(OAuthSignupTransaction::class.java)
        oauthAuthorizationUrlFactory = mock(OAuthAuthorizationUrlFactory::class.java)
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
                STATE_TTL_SECONDS,
                MAX_PENDING_PER_BROWSER,
            )

        member = mock(Member::class.java)
        `when`(member.id).thenReturn(1L)
        `when`(member.role).thenReturn(Role.ROLE_USER)
        `when`(member.status).thenReturn(MemberStatus.ACTIVE)
        `when`(member.isLocalLoginEnabled).thenReturn(true)
        `when`(member.password).thenReturn("encoded-password")
        `when`(jwtTokenProvider.createAccessToken(anyLong(), anyString())).thenReturn("test-access-token")
        `when`(jwtTokenProvider.createRefreshToken(anyLong())).thenReturn("test-refresh-token")
    }

    @Nested
    @DisplayName("signup — 검증·저장 순서")
    inner class Signup {
        private val request = SignupRequest("test@example.com", "password123", "tester", true, true)

        @Test
        fun `이메일 중복 - 이메일 인증 - 닉네임 중복 - encode - save - 동의이력 2건 순서다`() {
            `when`(emailVerificationRepository.existsByEmailAndVerifiedTrue("test@example.com")).thenReturn(true)
            `when`(passwordEncoder.encode("password123")).thenReturn("encoded-password")
            `when`(memberRepository.save(any(Member::class.java))).thenAnswer { it.getArgument<Member>(0) }

            authService.signup(request, "test-ip", "test-user-agent")

            val order =
                inOrder(memberRepository, emailVerificationRepository, passwordEncoder, memberAgreementRepository)
            order.verify(memberRepository).existsByEmail("test@example.com")
            order.verify(emailVerificationRepository).existsByEmailAndVerifiedTrue("test@example.com")
            order.verify(memberRepository).existsByNickname("tester")
            order.verify(passwordEncoder).encode("password123")
            order.verify(memberRepository).save(any(Member::class.java))
            order.verify(memberAgreementRepository, times(2)).save(any(MemberAgreement::class.java))
            order.verifyNoMoreInteractions()
        }

        /** 약관 검증이 가장 먼저다 — 미동의 요청은 어떤 저장소도 건드리지 않아야 한다. */
        @Test
        fun `약관 미동의면 어떤 협력자도 호출하지 않는다`() {
            val rejected = SignupRequest("test@example.com", "password123", "tester", false, true)

            val thrown = catchThrowable { authService.signup(rejected, "test-ip", "test-user-agent") }

            assertThat((thrown as BusinessException).errorCode).isEqualTo(ErrorCode.TERMS_NOT_AGREED)
            verifyNoInteractions(
                memberRepository,
                emailVerificationRepository,
                passwordEncoder,
                memberAgreementRepository,
            )
        }
    }

    @Nested
    @DisplayName("login — 차단 확인과 실패 카운트")
    inner class Login {
        private val request = LoginRequest("test@example.com", "password123")

        @Test
        fun `차단 확인 - 회원 조회 - 비밀번호 - 토큰 저장 - 성공 기록 순서다`() {
            `when`(memberRepository.findByEmail("test@example.com")).thenReturn(Optional.of(member))
            `when`(passwordEncoder.matches("password123", "encoded-password")).thenReturn(true)

            authService.login(request)

            val order = inOrder(loginAttemptService, memberRepository, passwordEncoder, refreshTokenService)
            order.verify(loginAttemptService).assertNotBlocked("test@example.com")
            order.verify(memberRepository).findByEmail("test@example.com")
            order.verify(passwordEncoder).matches("password123", "encoded-password")
            order.verify(refreshTokenService).saveOrReplace(1L, "test-refresh-token")
            order.verify(loginAttemptService).recordSuccess("test@example.com")
            order.verifyNoMoreInteractions()
        }

        /** 임계값 도달 시 자격증명 확인 **전에** 차단돼야 한다 — 회원 조회가 일어나면 안 된다. */
        @Test
        fun `차단된 이메일이면 회원 조회 자체가 없다`() {
            doThrow(BusinessException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS))
                .`when`(loginAttemptService)
                .assertNotBlocked("test@example.com")

            catchThrowable { authService.login(request) }

            verifyNoInteractions(memberRepository, passwordEncoder)
        }

        /** 소셜 전용 회원은 BCrypt 호출 없이 즉시 합류해야 한다 — 타이밍으로 회원 존재가 새면 안 된다. */
        @Test
        fun `소셜 전용 회원은 BCrypt 호출 없이 MEMBER_NOT_FOUND 로 합류하고 실패로 카운트된다`() {
            `when`(member.isLocalLoginEnabled).thenReturn(false)
            `when`(memberRepository.findByEmail("test@example.com")).thenReturn(Optional.of(member))

            val thrown = catchThrowable { authService.login(request) }

            assertThat((thrown as BusinessException).errorCode).isEqualTo(ErrorCode.MEMBER_NOT_FOUND)
            verifyNoInteractions(passwordEncoder)
            verify(loginAttemptService).recordFailure("test@example.com")
        }

        /** 탈퇴/정지 거부는 자격증명 추측 신호가 아니다 — 실패 횟수에 반영되면 안 된다. */
        @Test
        fun `탈퇴 회원 거부는 실패로 카운트하지 않는다`() {
            `when`(member.status).thenReturn(MemberStatus.DELETED)
            `when`(memberRepository.findByEmail("test@example.com")).thenReturn(Optional.of(member))

            val thrown = catchThrowable { authService.login(request) }

            assertThat((thrown as BusinessException).errorCode).isEqualTo(ErrorCode.DELETED_MEMBER)
            verify(loginAttemptService, never()).recordFailure(anyString())
        }
    }

    @Nested
    @DisplayName("startAuthorization — 저장과 인가 URL 의 일관성")
    inner class StartAuthorization {
        private val client = mock(OAuthClient::class.java)

        @Test
        fun `카카오는 nonce 없이 저장하고 저장에 성공해야 인가 URL 을 만든다`() {
            `when`(client.provider()).thenReturn(OAuthProvider.KAKAO)
            `when`(oauthAuthorizationUrlFactory.redirectUri(OAuthProvider.KAKAO))
                .thenReturn("https://example.test/callback/kakao")
            `when`(
                oauthStateRepository.issue(anyString(), anyObj(OAuthAuthorizationState::class.java), any(), anyInt()),
            ).thenReturn(true)
            `when`(oauthAuthorizationUrlFactory.build(eqObj(OAuthProvider.KAKAO), anyString(), anyString(), any()))
                .thenReturn("https://example.test/authorize")

            val result = authService.startAuthorization(client, "test-browser-hash")

            val stateCaptor = ArgumentCaptor.forClass(String::class.java)
            val valueCaptor: ArgumentCaptor<OAuthAuthorizationState> = ArgumentCaptor.forClass(OAuthAuthorizationState::class.java)
            val order = inOrder(oauthStateRepository, oauthAuthorizationUrlFactory)
            order
                .verify(oauthStateRepository)
                .issue(stateCaptor.capture(), cap(valueCaptor), eq(Duration.ofSeconds(STATE_TTL_SECONDS)), eq(MAX_PENDING_PER_BROWSER))
            order
                .verify(oauthAuthorizationUrlFactory)
                .build(eqObj(OAuthProvider.KAKAO), eq(stateCaptor.value), anyString(), isNull())

            assertThat(valueCaptor.value.provider).isEqualTo(OAuthProvider.KAKAO)
            assertThat(valueCaptor.value.browserCorrelationHash).isEqualTo("test-browser-hash")
            assertThat(valueCaptor.value.redirectUri).isEqualTo("https://example.test/callback/kakao")
            assertThat(valueCaptor.value.codeVerifier).isNotBlank()
            assertThat(valueCaptor.value.oidcNonce).isNull()
            assertThat(result.state).isEqualTo(stateCaptor.value)
            assertThat(result.expiresInSeconds).isEqualTo(STATE_TTL_SECONDS)
        }

        /** 구글만 OIDC nonce 를 발급하며, 저장값과 인가 URL 에 **같은 값**이 들어가야 검증이 성립한다. */
        @Test
        fun `구글은 같은 nonce 가 저장값과 인가 URL 에 들어간다`() {
            `when`(client.provider()).thenReturn(OAuthProvider.GOOGLE)
            `when`(oauthAuthorizationUrlFactory.redirectUri(OAuthProvider.GOOGLE))
                .thenReturn("https://example.test/callback/google")
            `when`(
                oauthStateRepository.issue(anyString(), anyObj(OAuthAuthorizationState::class.java), any(), anyInt()),
            ).thenReturn(true)
            `when`(oauthAuthorizationUrlFactory.build(eqObj(OAuthProvider.GOOGLE), anyString(), anyString(), anyString()))
                .thenReturn("https://example.test/authorize")

            authService.startAuthorization(client, "test-browser-hash")

            val valueCaptor: ArgumentCaptor<OAuthAuthorizationState> = ArgumentCaptor.forClass(OAuthAuthorizationState::class.java)
            val nonceCaptor = ArgumentCaptor.forClass(String::class.java)
            verify(oauthStateRepository).issue(anyString(), cap(valueCaptor), any(), anyInt())
            verify(oauthAuthorizationUrlFactory)
                .build(eqObj(OAuthProvider.GOOGLE), anyString(), anyString(), nonceCaptor.capture())

            assertThat(valueCaptor.value.oidcNonce).isNotBlank()
            assertThat(nonceCaptor.value).isEqualTo(valueCaptor.value.oidcNonce)
        }

        /** 저장 거부(브라우저별 상한)면 인가 URL 을 만들지 않아야 한다 — 사용 불가능한 URL 이 나가면 안 된다. */
        @Test
        fun `저장이 거부되면 TOO_MANY_OAUTH_ATTEMPTS 를 던지고 인가 URL 을 만들지 않는다`() {
            `when`(client.provider()).thenReturn(OAuthProvider.KAKAO)
            `when`(oauthAuthorizationUrlFactory.redirectUri(OAuthProvider.KAKAO))
                .thenReturn("https://example.test/callback/kakao")
            `when`(
                oauthStateRepository.issue(anyString(), anyObj(OAuthAuthorizationState::class.java), any(), anyInt()),
            ).thenReturn(false)

            val thrown = catchThrowable { authService.startAuthorization(client, "test-browser-hash") }

            assertThat((thrown as BusinessException).errorCode).isEqualTo(ErrorCode.TOO_MANY_OAUTH_ATTEMPTS)
            verify(oauthAuthorizationUrlFactory, never()).build(anyObj(OAuthProvider::class.java), anyString(), anyString(), any())
        }
    }

    @Nested
    @DisplayName("oauthLogin — state 소비와 제공자 호출 순서")
    inner class OAuthLogin {
        private val client = mock(OAuthClient::class.java)
        private val authState =
            OAuthAuthorizationState(
                OAuthProvider.KAKAO,
                "test-browser-hash",
                "https://example.test/callback/kakao",
                "test-code-verifier",
                null,
                Instant.now(),
            )
        private val identity = OAuthUserIdentity(OAuthProvider.KAKAO, "test-provider-user-id", "test@example.com")

        @BeforeEach
        fun stubClient() {
            `when`(client.provider()).thenReturn(OAuthProvider.KAKAO)
        }

        /** state 소비(1회용 무효화)가 제공자 호출보다 먼저다 — 순서가 바뀌면 실패한 시도의 state 가 재사용된다. */
        @Test
        fun `state 를 먼저 소비한 뒤 소비된 값 그대로 제공자를 호출한다`() {
            `when`(oauthStateRepository.consume("test-state", OAuthProvider.KAKAO, "test-browser-hash"))
                .thenReturn(Optional.of(authState))
            `when`(
                client.resolveIdentity(
                    "test-authorization-code",
                    "test-code-verifier",
                    "https://example.test/callback/kakao",
                    null,
                ),
            ).thenReturn(identity)
            stubExistingLink()

            authService.oauthLogin(client, "test-authorization-code", "test-state", "test-browser-hash")

            val order = inOrder(oauthStateRepository, client)
            order.verify(oauthStateRepository).consume("test-state", OAuthProvider.KAKAO, "test-browser-hash")
            order
                .verify(client)
                .resolveIdentity(
                    "test-authorization-code",
                    "test-code-verifier",
                    "https://example.test/callback/kakao",
                    null,
                )
        }

        @Test
        fun `기존 연동이 있으면 가입 트랜잭션을 호출하지 않는다`() {
            `when`(oauthStateRepository.consume("test-state", OAuthProvider.KAKAO, "test-browser-hash"))
                .thenReturn(Optional.of(authState))
            `when`(client.resolveIdentity(anyString(), anyString(), anyString(), any())).thenReturn(identity)
            stubExistingLink()

            authService.oauthLogin(client, "test-authorization-code", "test-state", "test-browser-hash")

            verifyNoInteractions(oauthSignupTransaction)
        }

        /** 신규 가입: 연동 조회 → 이메일 선점 확인 → signUp 순서. 선점 확인은 최적화일 뿐 정확성 근거가 아니다. */
        @Test
        fun `신규 계정이면 연동 조회 - 이메일 확인 - signUp 순서로 가입한다`() {
            `when`(oauthStateRepository.consume("test-state", OAuthProvider.KAKAO, "test-browser-hash"))
                .thenReturn(Optional.of(authState))
            `when`(client.resolveIdentity(anyString(), anyString(), anyString(), any())).thenReturn(identity)
            `when`(
                memberSocialAccountRepository
                    .findByProviderAndProviderUserIdFetchMember(OAuthProvider.KAKAO, "test-provider-user-id"),
            ).thenReturn(Optional.empty())
            `when`(oauthSignupTransaction.signUp(identity)).thenReturn(member)

            authService.oauthLogin(client, "test-authorization-code", "test-state", "test-browser-hash")

            val order = inOrder(memberSocialAccountRepository, memberRepository, oauthSignupTransaction)
            order
                .verify(memberSocialAccountRepository)
                .findByProviderAndProviderUserIdFetchMember(OAuthProvider.KAKAO, "test-provider-user-id")
            order.verify(memberRepository).existsByEmail("test@example.com")
            order.verify(oauthSignupTransaction).signUp(identity)
        }

        /** UNIQUE 위반 롤백 뒤에만 재조회한다 — 예외 메시지·벤더 문구 분석 없이 순서로만 처리한다. */
        @Test
        fun `signUp 이 UNIQUE 위반이면 reconcileAfterConflict 로 이어간다`() {
            `when`(oauthStateRepository.consume("test-state", OAuthProvider.KAKAO, "test-browser-hash"))
                .thenReturn(Optional.of(authState))
            `when`(client.resolveIdentity(anyString(), anyString(), anyString(), any())).thenReturn(identity)
            `when`(
                memberSocialAccountRepository
                    .findByProviderAndProviderUserIdFetchMember(OAuthProvider.KAKAO, "test-provider-user-id"),
            ).thenReturn(Optional.empty())
            `when`(oauthSignupTransaction.signUp(identity))
                .thenThrow(DataIntegrityViolationException("duplicate"))
            `when`(oauthSignupTransaction.reconcileAfterConflict(OAuthProvider.KAKAO, "test-provider-user-id"))
                .thenReturn(Optional.of(member))

            authService.oauthLogin(client, "test-authorization-code", "test-state", "test-browser-hash")

            val order = inOrder(oauthSignupTransaction)
            order.verify(oauthSignupTransaction).signUp(identity)
            order.verify(oauthSignupTransaction).reconcileAfterConflict(OAuthProvider.KAKAO, "test-provider-user-id")
        }

        private fun stubExistingLink() {
            val socialAccount = mock(MemberSocialAccount::class.java)
            `when`(socialAccount.member).thenReturn(member)
            `when`(
                memberSocialAccountRepository
                    .findByProviderAndProviderUserIdFetchMember(OAuthProvider.KAKAO, "test-provider-user-id"),
            ).thenReturn(Optional.of(socialAccount))
        }
    }

    @Nested
    @DisplayName("reissue·logout — 위임 순서")
    inner class ReissueAndLogout {
        @Test
        fun `reissue 는 검증 - 회원 조회 - 새 토큰 저장 순서다`() {
            `when`(refreshTokenService.validateAndGetMemberId("test-refresh-token")).thenReturn(1L)
            `when`(memberRepository.findById(1L)).thenReturn(Optional.of(member))
            `when`(jwtTokenProvider.createRefreshToken(anyLong())).thenReturn("test-rotated-refresh-token")

            authService.reissue("test-refresh-token")

            val order = inOrder(refreshTokenService, memberRepository)
            order.verify(refreshTokenService).validateAndGetMemberId("test-refresh-token")
            order.verify(memberRepository).findById(1L)
            order.verify(refreshTokenService).saveOrReplace(1L, "test-rotated-refresh-token")
            order.verifyNoMoreInteractions()
        }

        @Test
        fun `logout 은 저장된 Refresh Token 삭제만 위임한다`() {
            authService.logout(5L)

            verify(refreshTokenService).deleteByMemberId(5L)
            verifyNoInteractions(memberRepository, jwtTokenProvider)
        }
    }

    companion object {
        private const val STATE_TTL_SECONDS = 300L
        private const val MAX_PENDING_PER_BROWSER = 5

        /**
         * Mockito 매처는 null 을 반환하는데, Kotlin **non-null 파라미터** 자리에 넣으면 호출부
         * intrinsic null 검사("must not be null")가 매처 등록 전에 터진다. 매처를 등록한 뒤
         * 타입만 맞춘 값을 돌려주는 표준 우회다(mockito-kotlin 과 같은 방식).
         */
        @Suppress("UNCHECKED_CAST")
        fun <T> anyObj(type: Class<T>): T {
            any(type)
            return null as T
        }

        fun <T> eqObj(value: T): T {
            eq(value)
            return value
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> cap(captor: ArgumentCaptor<T>): T {
            captor.capture()
            return null as T
        }
    }
}
