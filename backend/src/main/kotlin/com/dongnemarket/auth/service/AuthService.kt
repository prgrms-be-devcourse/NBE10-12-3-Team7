package com.dongnemarket.auth.service

import com.dongnemarket.auth.client.OAuthAuthorizationUrlFactory
import com.dongnemarket.auth.client.OAuthClient
import com.dongnemarket.auth.client.OAuthUserIdentity
import com.dongnemarket.auth.dto.LoginRequest
import com.dongnemarket.auth.dto.LoginResponse
import com.dongnemarket.auth.dto.OAuthAuthorizationStart
import com.dongnemarket.auth.dto.SignupRequest
import com.dongnemarket.auth.dto.SignupResponse
import com.dongnemarket.auth.dto.TokenResponse
import com.dongnemarket.auth.entity.MemberSocialAccount
import com.dongnemarket.auth.entity.OAuthProvider
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.auth.repository.MemberSocialAccountRepository
import com.dongnemarket.auth.repository.OAuthAuthorizationState
import com.dongnemarket.auth.repository.OAuthStateRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.member.entity.AgreementType
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberAgreement
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberAgreementRepository
import com.dongnemarket.member.repository.MemberRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.Base64

/**
 * 인증 도메인의 orchestrator. 트랜잭션 경계 계약은 원본 Java 를 그대로 보존한다 —
 * 클래스 레벨 `@Transactional(readOnly = true)`, [signup]·[login]·[reissue]·[logout] 은 `@Transactional`,
 * [startAuthorization]·[oauthLogin] 은 `Propagation.NOT_SUPPORTED`.
 *
 * 전환 규칙 — public 메서드의 Java 참조형 파라미터는 nullable 로 두고, `!!` 는 원본이 처음 역참조하던
 * 지점에만 둔다. non-null 로 조이면 JVM descriptor 는 같지만 메서드 진입 시점에 null 검사가 삽입돼
 * 실패 위치가 앞당겨진다(5단계·6-1단계에서 확인한 회귀 계열).
 */
@Service
@Transactional(readOnly = true)
class AuthService(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenService: RefreshTokenService,
    private val emailVerificationRepository: EmailVerificationRepository,
    private val memberAgreementRepository: MemberAgreementRepository,
    private val loginAttemptService: LoginAttemptService,
    private val oauthStateRepository: OAuthStateRepository,
    private val memberSocialAccountRepository: MemberSocialAccountRepository,
    private val oauthSignupTransaction: OAuthSignupTransaction,
    private val oauthAuthorizationUrlFactory: OAuthAuthorizationUrlFactory,
    @param:Value("\${oauth.authorization-state.ttl-seconds}") oauthStateTtlSeconds: Long,
    @param:Value("\${oauth.authorization-state.max-pending-per-browser}") private val oauthMaxPendingPerBrowser: Int,
) {
    private val oauthStateTtl: Duration = Duration.ofSeconds(oauthStateTtlSeconds)
    private val secureRandom = SecureRandom()

    /**
     * @param ipAddress 약관 동의 이력 증적용. 요청자 식별 목적이 아니라 동의 시점 증빙 목적이다.
     * @param userAgent 약관 동의 이력 증적용(위와 동일한 목적).
     */
    @Transactional
    fun signup(
        request: SignupRequest?,
        ipAddress: String?,
        userAgent: String?,
    ): SignupResponse {
        if (!request!!.termsAgreed) {
            throw BusinessException(ErrorCode.TERMS_NOT_AGREED)
        }
        if (!request.personalInfoCollectionAgreed) {
            throw BusinessException(ErrorCode.PERSONAL_INFO_COLLECTION_NOT_AGREED)
        }
        if (memberRepository.existsByEmail(request.email)) {
            throw BusinessException(ErrorCode.DUPLICATE_EMAIL)
        }
        if (!emailVerificationRepository.existsByEmailAndVerifiedTrue(request.email)) {
            throw BusinessException(ErrorCode.EMAIL_NOT_VERIFIED)
        }
        if (memberRepository.existsByNickname(request.nickname)) {
            throw BusinessException(ErrorCode.DUPLICATE_NICKNAME)
        }

        val encodedPassword = passwordEncoder.encode(request.password)
        val member = Member.createUser(request.email, encodedPassword, request.nickname)

        return try {
            val savedMember = memberRepository.save(member)
            saveAgreements(savedMember, ipAddress, userAgent)
            SignupResponse.from(savedMember)
        } catch (e: DataIntegrityViolationException) {
            throw resolveDuplicateException(request, e)
        }
    }

    /** 필수 동의 항목(이용약관/개인정보 수집·이용) 각각을 별도 이력 row로 저장한다. */
    private fun saveAgreements(
        member: Member,
        ipAddress: String?,
        userAgent: String?,
    ) {
        val agreedAt = LocalDateTime.now()
        memberAgreementRepository.save(
            MemberAgreement.of(
                member,
                AgreementType.TERMS_OF_SERVICE,
                AGREEMENT_VERSION,
                agreedAt,
                ipAddress,
                userAgent,
            ),
        )
        memberAgreementRepository.save(
            MemberAgreement.of(
                member,
                AgreementType.PERSONAL_INFO_COLLECTION,
                AGREEMENT_VERSION,
                agreedAt,
                ipAddress,
                userAgent,
            ),
        )
    }

    /**
     * 로그인 실패(이메일 없음/비밀번호 불일치)만 실패 횟수에 반영한다 — 탈퇴/정지 회원 거부는 자격증명 추측
     * 신호가 아니므로 카운트하지 않는다. 임계값 도달 시 이후 로그인은 자격증명 확인 전에 즉시 차단된다.
     */
    @Transactional
    fun login(request: LoginRequest?): LoginResponse {
        val email = request!!.email
        loginAttemptService.assertNotBlocked(email)
        try {
            val member =
                memberRepository
                    .findByEmail(email)
                    .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
            // 소셜 전용 회원은 "존재하지 않는 이메일"과 완전히 같은 경로(BCrypt 호출 없이 즉시 MEMBER_NOT_FOUND)로
            // 합류시킨다 — INVALID_PASSWORD 쪽으로 보내면 "회원이 존재한다"는 신호가 응답/타이밍으로 새어나간다.
            if (!member.isLocalLoginEnabled) {
                throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
            }

            validateActiveStatus(member)
            if (!passwordEncoder.matches(request.password, member.password)) {
                throw BusinessException(ErrorCode.INVALID_PASSWORD)
            }

            val response = issueTokens(member)
            loginAttemptService.recordSuccess(email)
            return response
        } catch (e: BusinessException) {
            if (e.errorCode == ErrorCode.MEMBER_NOT_FOUND || e.errorCode == ErrorCode.INVALID_PASSWORD) {
                loginAttemptService.recordFailure(email)
            }
            throw e
        }
    }

    /**
     * 소셜 로그인 인가를 시작한다: state·PKCE code_verifier·(구글만) OIDC nonce를 발급해 Redis에 저장하고,
     * 프론트가 그대로 리다이렉트할 수 있는 완성된 인가 URL을 돌려준다.
     *
     * DB를 전혀 쓰지 않으므로(Redis만) 트랜잭션이 필요 없다 — 클래스 레벨
     * `@Transactional(readOnly = true)` 가 걸려도 문제되진 않지만, 의미상 맞지 않아 명시적으로 뺀다.
     *
     * `client!!` 가 state·code_verifier·code_challenge 생성 **뒤에** 오는 이유 — 원본 Java 에서 client 가
     * null 이면 난수 2회 발급과 SHA-256 해시가 끝난 뒤 `client.provider()` 에서 NPE 가 났다.
     *
     * @param browserCorrelationHash Controller가 `oauth_bcid` 쿠키 원문을 해시해 넘긴 값
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun startAuthorization(
        client: OAuthClient?,
        browserCorrelationHash: String?,
    ): OAuthAuthorizationStart {
        val state = generateUrlSafeRandom(32)
        val codeVerifier = generateUrlSafeRandom(64)
        val codeChallenge = base64UrlSha256(codeVerifier)
        val oidcNonce = if (client!!.provider() == OAuthProvider.GOOGLE) generateUrlSafeRandom(32) else null
        val redirectUri = oauthAuthorizationUrlFactory.redirectUri(client.provider())

        val value =
            OAuthAuthorizationState(
                client.provider(),
                browserCorrelationHash,
                redirectUri,
                codeVerifier,
                oidcNonce,
                Instant.now(),
            )
        val issued = oauthStateRepository.issue(state, value, oauthStateTtl, oauthMaxPendingPerBrowser)
        if (!issued) {
            throw BusinessException(ErrorCode.TOO_MANY_OAUTH_ATTEMPTS)
        }

        val authorizationUrl = oauthAuthorizationUrlFactory.build(client.provider(), state, codeChallenge, oidcNonce)
        return OAuthAuthorizationStart(authorizationUrl, state, oauthStateTtl.toSeconds())
    }

    private fun generateUrlSafeRandom(bytes: Int): String {
        val value = ByteArray(bytes)
        secureRandom.nextBytes(value)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }

    /**
     * PKCE S256: code_challenge = BASE64URL(SHA256(code_verifier))
     *
     * `toByteArray(Charset.defaultCharset())` 인 이유 — 원본 Java 의 인자 없는 `String.getBytes()` 는
     * 플랫폼 기본 charset 을 쓴다. Kotlin 의 인자 없는 `toByteArray()` 는 UTF-8 고정이라 의미가 다르다
     * (code_verifier 는 ASCII 라 실제 바이트는 같지만, 원본 의미를 그대로 옮긴다).
     */
    private fun base64UrlSha256(codeVerifier: String): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashed = digest.digest(codeVerifier.toByteArray(Charset.defaultCharset()))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e)
        }
    }

    /**
     * 소셜 로그인. state 소비(Redis, 원자적) → 제공자 토큰교환/신원확인(네트워크, DB 트랜잭션 밖) →
     * 기존 연동 조회 또는 신규가입 → 토큰 발급 순으로 진행한다.
     *
     * `Propagation.NOT_SUPPORTED` 로 이 메서드 자체는 트랜잭션을 열지 않는다 — 클래스 레벨
     * `@Transactional(readOnly = true)` 를 상속받으면 네트워크 호출(제공자 토큰교환)이 DB 커넥션을
     * 붙든 채로 일어나고, [OAuthSignupTransaction] 의 두 메서드가 서로 다른 트랜잭션으로 실행돼야
     * 하는 요구사항도 깨진다(상위 트랜잭션이 있으면 REQUIRED 전파로 같은 트랜잭션에 합류해버린다).
     *
     * @param client 호출할 provider의 [OAuthClient]. Controller가 리터럴 엔드포인트별로 고정해서 넘긴다
     *     (provider가 사용자 입력으로 결정되는 지점이 없다).
     * @param browserCorrelationHash `oauth_bcid` 쿠키 원문을 해시한 값
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun oauthLogin(
        client: OAuthClient?,
        code: String?,
        state: String?,
        browserCorrelationHash: String?,
    ): LoginResponse {
        val authState =
            oauthStateRepository
                .consume(state, client!!.provider(), browserCorrelationHash)
                .orElseThrow { BusinessException(ErrorCode.INVALID_OAUTH_STATE) }

        // state는 이미 소비됐다 — 아래에서 제공자 호출이 실패(timeout/장애)해도 이 state는 복구·재사용하지
        // 않는다. 사용자는 새 OAuth 흐름을 처음부터 다시 시작해야 한다.
        val identity =
            client.resolveIdentity(
                code,
                authState.codeVerifier,
                authState.redirectUri,
                authState.oidcNonce,
            )

        val member =
            memberSocialAccountRepository
                .findByProviderAndProviderUserIdFetchMember(identity.provider, identity.providerUserId)
                .map(MemberSocialAccount::member)
                .orElseGet { signUpOrReconcile(identity) }

        validateActiveStatus(member)
        return issueTokens(member)
    }

    /**
     * 신규 소셜 가입을 시도한다. 사전 이메일 존재 체크는 흔한 경우를 빠르게 걸러내는 최적화일 뿐,
     * 정확성의 근거가 아니다 — 실제 동시성 안전성은 DB UNIQUE 제약 + 위반 시 재조회에서 나온다.
     *
     * [OAuthSignupTransaction.signUp] 이 UNIQUE 위반으로 예외를 던지면 그 트랜잭션은 이미
     * 완전히 롤백된 상태다(rollback-only 트랜잭션 안에서 재조회하지 않는다). 이 메서드(트랜잭션 없음)가
     * 그 예외를 받아 [OAuthSignupTransaction.reconcileAfterConflict] 로 새 read-only 트랜잭션을
     * 연다 — 동일 연동이 동시 요청으로 먼저 만들어졌으면 그 회원으로 로그인을 이어가고, 아니면(이메일 등
     * 다른 제약 충돌) 공통 에러로 응답한다. 예외 메시지나 DB 벤더별 문구에는 의존하지 않는다.
     */
    private fun signUpOrReconcile(identity: OAuthUserIdentity): Member {
        if (memberRepository.existsByEmail(identity.email)) {
            throw BusinessException(ErrorCode.OAUTH_EMAIL_CONFLICT)
        }
        return try {
            oauthSignupTransaction.signUp(identity)
        } catch (e: DataIntegrityViolationException) {
            oauthSignupTransaction
                .reconcileAfterConflict(identity.provider, identity.providerUserId)
                .orElseThrow { BusinessException(ErrorCode.OAUTH_EMAIL_CONFLICT) }
        }
    }

    /**
     * `member` 가 nullable 인 이유 — [oauthLogin] 에서 [MemberSocialAccount.member] (Kotlin 엔티티의
     * nullable 프로퍼티)가 흘러들어온다. 원본 Java 는 member 가 null 이면 `member.getId()` 역참조
     * 지점에서 NPE 가 났으므로 `!!` 를 같은 지점에 둔다.
     */
    private fun issueTokens(member: Member?): LoginResponse {
        val accessToken = jwtTokenProvider.createAccessToken(member!!.id, member.role.name)
        val refreshToken = jwtTokenProvider.createRefreshToken(member.id)
        refreshTokenService.saveOrReplace(member.id, refreshToken)
        return LoginResponse.of(accessToken, refreshToken)
    }

    /**
     * Refresh Token을 검증하고 Access Token과 Refresh Token을 함께 재발급한다(Rotation).
     *
     * 기존 Refresh Token은 검증 즉시 저장소에서 새 값으로 교체돼 무효화된다 — 탈취된 옛 토큰이 재사용되면
     * (이미 교체된 뒤라) 저장값과 불일치해 실패하므로, 재사용을 탐지하는 효과도 있다.
     */
    @Transactional
    fun reissue(refreshToken: String?): TokenResponse {
        // findById 는 Spring @NonNullApi 라 non-null Long 을 요구한다. 원본 Java 는 이 값이 null 이면
        // findById 내부 Assert.notNull 이 IllegalArgumentException 을 던졌으므로 같은 예외 계열인
        // requireNotNull 을 쓴다. 실제로는 validateAndGetMemberId 가 모든 실패 경로에서
        // BusinessException 을 던지고 non-null 을 반환하므로 이 지점은 도달 불가다.
        val memberId = refreshTokenService.validateAndGetMemberId(refreshToken)
        val member =
            memberRepository
                .findById(requireNotNull(memberId))
                .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        validateActiveStatus(member)

        val newAccessToken = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val newRefreshToken = jwtTokenProvider.createRefreshToken(member.id)
        refreshTokenService.saveOrReplace(member.id, newRefreshToken)
        return TokenResponse.of(newAccessToken, newRefreshToken)
    }

    /**
     * 로그아웃: 저장된 Refresh Token만 삭제한다(멱등 — 여러 번 호출해도 항상 성공).
     *
     * Access Token 자체는 서버에서 즉시 무효화하지 않는다(Stateless JWT 정책 유지) —
     * 이미 발급된 Access Token은 만료 시각(최대 15분)까지 그대로 유효하며, 그 사이 재발급만 막힌다.
     */
    @Transactional
    fun logout(memberId: Long?) {
        refreshTokenService.deleteByMemberId(memberId)
    }

    /**
     * 탈퇴/정지 회원은 로그인/재발급 모두 불가 (login()과 reissue()의 정책을 일관되게 유지).
     * `member` 가 nullable 인 이유는 [issueTokens] 와 같다 — 원본 첫 역참조 지점(`member.getStatus()`)에
     * `!!` 를 둔다.
     */
    private fun validateActiveStatus(member: Member?) {
        if (member!!.status == MemberStatus.DELETED) {
            throw BusinessException(ErrorCode.DELETED_MEMBER)
        }
        if (member.status == MemberStatus.SUSPENDED) {
            throw BusinessException(ErrorCode.SUSPENDED_MEMBER)
        }
    }

    /** 중복 체크 이후 save() 사이의 race condition으로 unique 제약을 위반한 경우, 원인을 재조회해 알맞은 BusinessException으로 변환한다. */
    private fun resolveDuplicateException(
        request: SignupRequest,
        e: DataIntegrityViolationException,
    ): BusinessException {
        if (memberRepository.existsByEmail(request.email)) {
            return BusinessException(ErrorCode.DUPLICATE_EMAIL)
        }
        if (memberRepository.existsByNickname(request.nickname)) {
            return BusinessException(ErrorCode.DUPLICATE_NICKNAME)
        }
        throw e
    }

    companion object {
        /** 약관/개인정보 동의 버전. 별도 버전 관리 테이블 없이 우선 고정값으로 둔다(이후 약관 개정 시 재검토). */
        private const val AGREEMENT_VERSION = "v1.0"
    }
}
