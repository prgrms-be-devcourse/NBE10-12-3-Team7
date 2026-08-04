package com.dongnemarket.auth.service

import com.dongnemarket.auth.client.OAuthUserIdentity
import com.dongnemarket.auth.entity.MemberSocialAccount
import com.dongnemarket.auth.entity.OAuthProvider
import com.dongnemarket.auth.repository.MemberSocialAccountRepository
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.Optional

/**
 * 소셜 로그인 최초 가입의 트랜잭션 경계를 [AuthService](비-트랜잭션 orchestrator)로부터 분리한 컴포넌트.
 * [signUp] 과 [reconcileAfterConflict] 는 서로 다른 Spring 트랜잭션으로 실행돼야 하므로 **반드시 별도 빈으로
 * 존재한다** — 같은 빈 안에서 self-invocation 으로 호출하면 `@Transactional` 프록시가 가로채지 못해
 * 트랜잭션 경계가 분리되지 않는다.
 *
 * 전환 규칙 — 파라미터는 원본 Java 가 참조형이라 nullable 로 둔다. non-null 로 조이면 JVM descriptor 는
 * 같지만 **메서드 진입 시점에 null 검사가 삽입**돼, 원본에서 `passwordEncoder.encode(...)` 가 먼저 실행된 뒤
 * `identity.provider()` 에서 NPE 가 나던 순서가 사라진다(5단계에서 같은 계열의 회귀를 겪었다).
 *
 * [OAuthUserIdentity] 는 1단계에서 Kotlin `@JvmRecord` 가 됐다 — Kotlin 에서는 `provider()` 가 아니라
 * 프로퍼티 `provider` 로 접근한다. JVM 외부 표면은 그대로다.
 */
@Component
class OAuthSignupTransaction(
    private val memberRepository: MemberRepository,
    private val memberSocialAccountRepository: MemberSocialAccountRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    private val secureRandom = SecureRandom()

    /**
     * Member 와 MemberSocialAccount 를 하나의 신규 가입 트랜잭션으로 생성한다. 성공하거나 전체 롤백된다.
     *
     * UNIQUE 위반(email, provider+provider_user_id, 극히 드물게 nickname)은 **여기서 잡지 않고 그대로 던진다** —
     * 이 메서드가 예외로 끝나면 Spring 이 이 트랜잭션 전체를 롤백한 뒤 예외를 호출부로 전파한다.
     * 호출부(트랜잭션 밖)가 [reconcileAfterConflict] 로 재조회를 이어간다.
     */
    @Transactional
    fun signUp(identity: OAuthUserIdentity?): Member {
        val dummyPassword = passwordEncoder.encode(generateDummySecret())
        val nickname = generateNickname(identity!!.provider)
        val member = Member.createSocialUser(identity.email!!, dummyPassword, nickname)
        memberRepository.save(member)
        memberSocialAccountRepository.save(
            MemberSocialAccount.of(member, identity.provider, identity.providerUserId),
        )
        return member
    }

    /**
     * [signUp] 이 UNIQUE 위반으로 롤백된 뒤, 완전히 새로운 read-only 트랜잭션에서
     * `(provider, provider_user_id)` 를 재조회한다. 동시 요청이 먼저 만든 연동이면 그 회원을
     * 반환하고(로그인으로 이어감), 없으면 다른 제약(이메일 등) 충돌이라는 뜻이라 empty 를 반환한다
     * (호출부가 `OAUTH_EMAIL_CONFLICT` 로 처리).
     *
     * 반환 타입은 `Optional<Member>` 그대로다 — 아직 Java 인 `AuthService` 가 `Optional` API 를 직접 쓴다.
     */
    @Transactional(readOnly = true)
    fun reconcileAfterConflict(
        provider: OAuthProvider?,
        providerUserId: String?,
    ): Optional<Member> =
        memberSocialAccountRepository
            .findByProviderAndProviderUserIdFetchMember(provider, providerUserId)
            .map(MemberSocialAccount::member)

    /** 로그인에 쓰이지 않는 더미 비밀번호. 원문은 encode() 직후 버려지고 저장·로그되지 않는다. */
    private fun generateDummySecret(): String {
        val bytes = ByteArray(DUMMY_PASSWORD_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * provider 명 접두어 + 무작위 문자열. `members.nickname` UNIQUE(20자 제한)에 여유 있게 들어간다.
     *
     * `lowercase(Locale.getDefault())` 인 이유 — 원본 Java 의 `String.toLowerCase()` 가 기본 로케일을 쓴다.
     * Kotlin 의 인자 없는 `lowercase()` 는 `Locale.ROOT` 라 의미가 달라진다. 로케일 정책을 개선하지 않고
     * 원본 동작을 그대로 옮긴다.
     *
     * `provider` 가 nullable 인 이유 — 원본 Java 에서 `provider` 가 null 이면 StringBuilder 생성과 난수
     * 10회 소비가 **먼저 끝난 뒤** `provider.name()` 에서 NPE 가 났다. non-null 로 조이면 메서드 진입
     * 시점으로 실패가 앞당겨져 `secureRandom` 소비량까지 달라진다.
     */
    private fun generateNickname(provider: OAuthProvider?): String {
        val random = StringBuilder(NICKNAME_RANDOM_LENGTH)
        for (i in 0 until NICKNAME_RANDOM_LENGTH) {
            random.append(NICKNAME_ALPHABET[secureRandom.nextInt(NICKNAME_ALPHABET.length)])
        }
        return provider!!.name.lowercase(Locale.getDefault()) + "_" + random
    }

    companion object {
        private const val DUMMY_PASSWORD_BYTES = 32
        private const val NICKNAME_RANDOM_LENGTH = 10
        private const val NICKNAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
