package com.dongnemarket.auth.entity

import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.auth.repository.JpaRefreshTokenRepository
import com.dongnemarket.auth.repository.MemberSocialAccountRepository
import com.dongnemarket.auth.repository.RefreshTokenJpaEntityRepository
import com.dongnemarket.global.config.JpaAuditingConfig
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

/**
 * 2단계로 옮긴 영속성 타입이 **실제 Hibernate 컨텍스트에서 매핑되고 동작하는지** 확인한다.
 *
 * [AuthPersistenceJvmSurfaceTest] 가 "선언이 그대로인가"를 리플렉션으로 본다면, 여기서는
 * "그 선언으로 실제 스키마가 만들어지고 저장·조회가 되는가"를 본다. Kotlin 전환에서 조용히 깨질 수 있는
 * 자리 — `@field:` 누락으로 어노테이션이 엉뚱한 곳에 붙거나, noarg 플러그인이 기본 생성자를 못 만들거나,
 * allOpen 이 빠져 프록시 생성이 실패하거나, enum 저장 방식이 ORDINAL 로 바뀌는 경우 — 는 전부
 * 컨텍스트 부팅이나 첫 저장에서 드러난다.
 */
@DataJpaTest
@Import(JpaAuditingConfig::class)
@ActiveProfiles("test")
class AuthPersistenceMappingTest {
    @Autowired
    lateinit var emailVerificationRepository: EmailVerificationRepository

    @Autowired
    lateinit var memberSocialAccountRepository: MemberSocialAccountRepository

    @Autowired
    lateinit var refreshTokenJpaEntityRepository: RefreshTokenJpaEntityRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var entityManager: EntityManager

    @Test
    fun `세 entity 가 Hibernate 메타모델에 등록된다`() {
        val managed = entityManager.metamodel.entities.map { it.javaType }
        assertThat(managed).contains(
            EmailVerification::class.java,
            MemberSocialAccount::class.java,
            RefreshToken::class.java,
        )
    }

    @Nested
    @DisplayName("EmailVerification — 저장·조회와 파생 쿼리")
    inner class EmailVerificationPersistence {
        @Test
        fun `저장 후 다시 읽으면 인증 상태와 시각이 그대로다`() {
            val verifiedAt = LocalDateTime.of(2026, 1, 1, 12, 0)
            val saved = emailVerificationRepository.save(EmailVerification.verified("a@b.com", verifiedAt))
            entityManager.flush()
            entityManager.clear()

            val found = emailVerificationRepository.findById(saved.id!!).orElseThrow()
            assertThat(found.email).isEqualTo("a@b.com")
            assertThat(found.verified).isTrue()
            assertThat(found.verifiedAt).isEqualTo(verifiedAt)
            assertThat(found.createdAt).describedAs("BaseTimeEntity 감사 필드").isNotNull()
        }

        @Test
        fun `findByEmail 파생 쿼리가 동작한다`() {
            emailVerificationRepository.save(EmailVerification.verified("find@b.com", LocalDateTime.now()))
            entityManager.flush()

            assertThat(emailVerificationRepository.findByEmail("find@b.com")).isPresent()
            assertThat(emailVerificationRepository.findByEmail("none@b.com")).isEmpty()
        }

        /** 속성명이 `verified` 로 유지돼야만 이 파생 쿼리가 해석된다. */
        @Test
        fun `existsByEmailAndVerifiedTrue 파생 쿼리가 인증 상태를 구분한다`() {
            emailVerificationRepository.save(EmailVerification.verified("yes@b.com", LocalDateTime.now()))
            val unverified = EmailVerification.verified("no@b.com", LocalDateTime.now())
            unverified.unverify()
            emailVerificationRepository.save(unverified)
            entityManager.flush()

            assertThat(emailVerificationRepository.existsByEmailAndVerifiedTrue("yes@b.com")).isTrue()
            assertThat(emailVerificationRepository.existsByEmailAndVerifiedTrue("no@b.com")).isFalse()
        }

        @Test
        fun `unverify 로 인증 상태를 되돌리면 dirty checking 으로 반영된다`() {
            val saved = emailVerificationRepository.save(EmailVerification.verified("u@b.com", LocalDateTime.now()))
            saved.unverify()
            entityManager.flush()
            entityManager.clear()

            val found = emailVerificationRepository.findById(saved.id!!).orElseThrow()
            assertThat(found.verified).isFalse()
            assertThat(found.verifiedAt).isNull()
        }
    }

    @Nested
    @DisplayName("MemberSocialAccount — 연관관계와 enum 저장")
    inner class MemberSocialAccountPersistence {
        @Test
        fun `provider 가 문자열로 저장된다 - EnumType STRING`() {
            val member = memberRepository.save(newMember("social@b.com"))
            val saved =
                memberSocialAccountRepository.save(
                    MemberSocialAccount.of(member, OAuthProvider.KAKAO, "kakao-1"),
                )
            entityManager.flush()

            val stored =
                entityManager
                    .createNativeQuery(
                        "select provider from member_social_accounts where id = :id",
                    ).setParameter("id", saved.id)
                    .singleResult
            assertThat(stored.toString()).isEqualTo("KAKAO")
        }

        @Test
        fun `fetch join 쿼리로 Member 까지 한 번에 가져온다`() {
            val member = memberRepository.save(newMember("fetch@b.com"))
            memberSocialAccountRepository.save(
                MemberSocialAccount.of(member, OAuthProvider.GOOGLE, "google-1"),
            )
            entityManager.flush()
            entityManager.clear()

            val found =
                memberSocialAccountRepository
                    .findByProviderAndProviderUserIdFetchMember(OAuthProvider.GOOGLE, "google-1")
                    .orElseThrow()
            assertThat(found.provider).isEqualTo(OAuthProvider.GOOGLE)
            assertThat(found.providerUserId).isEqualTo("google-1")
            assertThat(found.member?.email).describedAs("fetch join 이라 초기화돼 있어야 한다").isEqualTo("fetch@b.com")
        }

        /** LAZY 연관이 프록시로 채워지는지 — 엔티티나 getter 가 final 이면 여기서 깨진다. */
        @Test
        fun `member 연관이 지연 로딩 프록시로 채워진다`() {
            val member = memberRepository.save(newMember("lazy@b.com"))
            val saved =
                memberSocialAccountRepository.save(
                    MemberSocialAccount.of(member, OAuthProvider.KAKAO, "kakao-lazy"),
                )
            entityManager.flush()
            entityManager.clear()

            val found = memberSocialAccountRepository.findById(saved.id!!).orElseThrow()
            assertThat(found.member?.email).isEqualTo("lazy@b.com")
        }
    }

    @Nested
    @DisplayName("RefreshToken — 저장소 구현과 교체 정책")
    inner class RefreshTokenPersistence {
        @Test
        fun `발급 후 memberId 로 조회된다`() {
            val expiresAt = LocalDateTime.of(2026, 6, 1, 0, 0)
            refreshTokenJpaEntityRepository.save(RefreshToken.issue(1L, "token-1", expiresAt))
            entityManager.flush()
            entityManager.clear()

            val found = refreshTokenJpaEntityRepository.findByMemberId(1L).orElseThrow()
            assertThat(found.token).isEqualTo("token-1")
            assertThat(found.expiresAt).isEqualTo(expiresAt)
            assertThat(found.matches("token-1")).isTrue()
            assertThat(found.matches("other")).isFalse()
        }

        @Test
        fun `deleteByMemberId 로 삭제된다`() {
            refreshTokenJpaEntityRepository.save(RefreshToken.issue(2L, "token-2", LocalDateTime.now()))
            entityManager.flush()

            refreshTokenJpaEntityRepository.deleteByMemberId(2L)
            entityManager.flush()
            entityManager.clear()

            assertThat(refreshTokenJpaEntityRepository.findByMemberId(2L)).isEmpty()
        }

        /** 회원당 1행 정책 — 재발급 시 새 row 를 만들지 않고 기존 row 를 교체한다. */
        @Test
        fun `JpaRefreshTokenRepository 는 재발급 시 기존 row 를 교체한다`() {
            val repository = JpaRefreshTokenRepository(refreshTokenJpaEntityRepository)
            val first = repository.save(RefreshToken.issue(3L, "first", LocalDateTime.of(2026, 1, 1, 0, 0)))
            entityManager.flush()

            val replaced = repository.save(RefreshToken.issue(3L, "second", LocalDateTime.of(2026, 2, 1, 0, 0)))
            entityManager.flush()
            entityManager.clear()

            assertThat(replaced.id).describedAs("같은 row 를 재사용해야 한다").isEqualTo(first.id)
            assertThat(refreshTokenJpaEntityRepository.findAll()).hasSize(1)
            assertThat(repository.findByMemberId(3L).orElseThrow().token).isEqualTo("second")
        }
    }

    private fun newMember(email: String): Member = Member.createUser(email, "encoded-password", "nick-${email.substringBefore('@')}")
}
