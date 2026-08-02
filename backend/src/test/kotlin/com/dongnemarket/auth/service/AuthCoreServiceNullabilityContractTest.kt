package com.dongnemarket.auth.service

import com.dongnemarket.auth.client.OAuthAuthorizationUrlFactory
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.auth.repository.MemberSocialAccountRepository
import com.dongnemarket.auth.repository.OAuthStateRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.member.repository.MemberAgreementRepository
import com.dongnemarket.member.repository.MemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.valueParameters

/**
 * 6단계 핵심 서비스의 **Kotlin nullability 계약**을 고정한다.
 *
 * 이 회귀는 `javap` descriptor 비교로 잡히지 않는다 — nullability 는 `@Metadata` 에 실리므로
 * `String` 과 `String?` 은 같은 descriptor 다. non-null 로 조이면 **메서드 진입 시점에 null 검사가
 * 삽입**돼, 원본 Java 가 첫 역참조 지점에서 실패하던 위치가 앞당겨진다(5단계에서 확인한 회귀 계열).
 *
 * 원본(`556c3ac`) 의 null 경로:
 * - `signup(null, ...)` — `request.isTermsAgreed()` 에서 **NPE**. 어떤 협력자도 호출되지 않는다.
 * - `login(null)` — 첫 줄 `request.getEmail()` 에서 **NPE**. 차단 확인조차 호출되지 않는다.
 * - `startAuthorization(null, ...)` — 난수 2회·SHA-256 해시가 **끝난 뒤** `client.provider()` 에서 NPE.
 *   state 저장(issue)은 호출되지 않는다.
 * - `oauthLogin(null, ...)` — `consume` 의 **인자 평가 중** `client.provider()` 에서 NPE.
 *   저장소의 consume 자체가 호출되지 않는다.
 * - `OAuthSignupTransaction.signUp(null)` — `passwordEncoder.encode(...)` 가 **먼저 실행된 뒤**
 *   `identity.provider()` 에서 NPE. 회원·연동 저장은 일어나지 않는다.
 * - `logout(null)` / `reconcileAfterConflict(null, null)` — NPE 없이 null 그대로 위임된다.
 */
class AuthCoreServiceNullabilityContractTest {
    @Nested
    @DisplayName("public 파라미터 — 원본 Java 참조형은 nullable 로 남는다")
    inner class ReferenceParametersStayNullable {
        @Test
        fun `AuthService public 메서드의 참조형 파라미터가 전부 nullable 이다`() {
            for (
            (function, index) in
            listOf(
                "signup" to 0,
                "signup" to 1,
                "signup" to 2,
                "login" to 0,
                "startAuthorization" to 0,
                "startAuthorization" to 1,
                "oauthLogin" to 0,
                "oauthLogin" to 1,
                "oauthLogin" to 2,
                "oauthLogin" to 3,
                "reissue" to 0,
                "logout" to 0,
            )
            ) {
                assertThat(isNullableParam(AuthService::class, function, index))
                    .describedAs("%s 의 %d 번째 파라미터", function, index)
                    .isTrue()
            }
        }

        @Test
        fun `OAuthSignupTransaction 파라미터가 nullable 이다`() {
            assertThat(isNullableParam(OAuthSignupTransaction::class, "signUp", 0)).isTrue()
            assertThat(isNullableParam(OAuthSignupTransaction::class, "reconcileAfterConflict", 0)).isTrue()
            assertThat(isNullableParam(OAuthSignupTransaction::class, "reconcileAfterConflict", 1)).isTrue()
        }
    }

    /**
     * 리플렉션만으로는 "Kotlin 소스에서 실제로 호출 가능한가"를 증명하지 못한다.
     * 아래는 **컴파일되는 호출 fixture** 다 — 파라미터가 non-null 로 좁혀지면 이 파일이 컴파일되지 않는다.
     */
    @Nested
    @DisplayName("Kotlin 호출부가 인위적인 !! 없이 nullable 을 전달할 수 있다")
    inner class NullableCallSitesCompile {
        @Test
        fun `AuthService 와 OAuthSignupTransaction 호출이 컴파일된다`() {
            val nullString: String? = null
            val nullMemberId: Long? = null
            val service = mock(AuthService::class.java)
            val transaction = mock(OAuthSignupTransaction::class.java)

            service.signup(null, nullString, nullString)
            service.login(null)
            service.startAuthorization(null, nullString)
            service.oauthLogin(null, nullString, nullString, nullString)
            service.reissue(nullString)
            service.logout(nullMemberId)
            transaction.signUp(null)
            transaction.reconcileAfterConflict(null, nullString)

            assertThat(service).isNotNull()
        }
    }

    @Nested
    @DisplayName("AuthService — 원본과 같은 지점에서 실패한다")
    inner class AuthServiceNullPaths {
        private lateinit var memberRepository: MemberRepository
        private lateinit var passwordEncoder: PasswordEncoder
        private lateinit var refreshTokenService: RefreshTokenService
        private lateinit var emailVerificationRepository: EmailVerificationRepository
        private lateinit var memberAgreementRepository: MemberAgreementRepository
        private lateinit var loginAttemptService: LoginAttemptService
        private lateinit var oauthStateRepository: OAuthStateRepository
        private lateinit var memberSocialAccountRepository: MemberSocialAccountRepository
        private lateinit var oauthAuthorizationUrlFactory: OAuthAuthorizationUrlFactory
        private lateinit var authService: AuthService

        @BeforeEach
        fun setUp() {
            memberRepository = mock(MemberRepository::class.java)
            passwordEncoder = mock(PasswordEncoder::class.java)
            refreshTokenService = mock(RefreshTokenService::class.java)
            emailVerificationRepository = mock(EmailVerificationRepository::class.java)
            memberAgreementRepository = mock(MemberAgreementRepository::class.java)
            loginAttemptService = mock(LoginAttemptService::class.java)
            oauthStateRepository = mock(OAuthStateRepository::class.java)
            memberSocialAccountRepository = mock(MemberSocialAccountRepository::class.java)
            oauthAuthorizationUrlFactory = mock(OAuthAuthorizationUrlFactory::class.java)
            authService =
                AuthService(
                    memberRepository,
                    passwordEncoder,
                    mock(JwtTokenProvider::class.java),
                    refreshTokenService,
                    emailVerificationRepository,
                    memberAgreementRepository,
                    loginAttemptService,
                    oauthStateRepository,
                    memberSocialAccountRepository,
                    mock(OAuthSignupTransaction::class.java),
                    oauthAuthorizationUrlFactory,
                    300L,
                    5,
                )
        }

        @Test
        fun `signup null request 는 NPE 계열로 실패하고 어떤 협력자도 호출하지 않는다`() {
            val thrown = runCatching { authService.signup(null, "test-ip", "test-user-agent") }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(NullPointerException::class.java)
            assertThat(thrown).isNotInstanceOf(BusinessException::class.java)
            verifyNoInteractions(memberRepository, emailVerificationRepository, passwordEncoder, memberAgreementRepository)
        }

        /** 원본 첫 줄 `request.getEmail()` 에서 실패했다 — 차단 확인이 호출되면 실패 위치가 뒤로 밀린 것이다. */
        @Test
        fun `login null request 는 차단 확인 전에 NPE 계열로 실패한다`() {
            val thrown = runCatching { authService.login(null) }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(NullPointerException::class.java)
            verifyNoInteractions(loginAttemptService, memberRepository, passwordEncoder)
        }

        @Test
        fun `startAuthorization null client 는 state 저장 전에 NPE 계열로 실패한다`() {
            val thrown = runCatching { authService.startAuthorization(null, "test-browser-hash") }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(NullPointerException::class.java)
            assertThat(thrown).isNotInstanceOf(BusinessException::class.java)
            verifyNoInteractions(oauthStateRepository, oauthAuthorizationUrlFactory)
        }

        /** 원본도 `consume` 의 인자 평가(`client.provider()`) 중 실패해 저장소가 호출되지 않았다. */
        @Test
        fun `oauthLogin null client 는 state 소비 전에 NPE 계열로 실패한다`() {
            val thrown =
                runCatching {
                    authService.oauthLogin(null, "test-authorization-code", "test-state", "test-browser-hash")
                }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(NullPointerException::class.java)
            verifyNoInteractions(oauthStateRepository, memberSocialAccountRepository)
        }

        /** 원본은 null 을 그대로 위임했다 — 진입 null 검사가 생기면 멱등 로그아웃 의미가 바뀐다. */
        @Test
        fun `logout null memberId 는 NPE 없이 그대로 위임한다`() {
            authService.logout(null)

            verify(refreshTokenService).deleteByMemberId(null)
            verifyNoMoreInteractions(refreshTokenService)
        }
    }

    @Nested
    @DisplayName("OAuthSignupTransaction — 원본과 같은 지점에서 실패한다")
    inner class SignupTransactionNullPaths {
        private lateinit var memberRepository: MemberRepository
        private lateinit var memberSocialAccountRepository: MemberSocialAccountRepository
        private lateinit var passwordEncoder: PasswordEncoder
        private lateinit var transaction: OAuthSignupTransaction

        @BeforeEach
        fun setUp() {
            memberRepository = mock(MemberRepository::class.java)
            memberSocialAccountRepository = mock(MemberSocialAccountRepository::class.java)
            passwordEncoder = mock(PasswordEncoder::class.java)
            transaction = OAuthSignupTransaction(memberRepository, memberSocialAccountRepository, passwordEncoder)
        }

        /** 원본은 더미 비밀번호 encode 가 **먼저** 실행된 뒤 `identity.provider()` 에서 실패했다. */
        @Test
        fun `signUp null identity 는 encode 실행 후 NPE 계열로 실패하고 저장은 일어나지 않는다`() {
            `when`(passwordEncoder.encode(anyString())).thenReturn("encoded-dummy")

            val thrown = runCatching { transaction.signUp(null) }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(NullPointerException::class.java)
            assertThat(thrown).isNotInstanceOf(BusinessException::class.java)
            verify(passwordEncoder).encode(anyString())
            verifyNoInteractions(memberRepository, memberSocialAccountRepository)
        }

        /** 원본은 null 을 repository 까지 그대로 위임했다(5단계에서 복원한 nullable 파라미터 계약). */
        @Test
        fun `reconcileAfterConflict null 은 NPE 없이 repository 로 위임된다`() {
            val result = transaction.reconcileAfterConflict(null, null)

            assertThat(result).isEmpty()
            verify(memberSocialAccountRepository).findByProviderAndProviderUserIdFetchMember(null, null)
        }
    }

    private fun isNullableParam(
        type: KClass<*>,
        functionName: String,
        index: Int,
    ): Boolean {
        val function =
            type.declaredFunctions.firstOrNull { it.name == functionName }
                ?: error("$functionName 을 ${type.simpleName} 에서 찾지 못했다")
        return function.valueParameters[index].type.isMarkedNullable
    }
}
