package com.dongnemarket.auth.entity

import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.auth.repository.JpaRefreshTokenRepository
import com.dongnemarket.auth.repository.MemberSocialAccountRepository
import com.dongnemarket.auth.repository.RefreshTokenJpaEntityRepository
import com.dongnemarket.global.common.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.lang.reflect.Modifier
import java.time.LocalDateTime
import java.util.Optional

/**
 * auth 영속성 타입 7개(entity 3 · JPA repository 4)의 **JVM 공개 표면과 JPA 매핑**을 고정한다.
 *
 * 2단계 전환은 아직 Java 인 호출부(`AuthService`·`EmailVerificationService`·`RefreshTokenService`)와
 * DB 스키마를 그대로 둔 채 언어만 바꾸는 작업이라, 다음 세 가지가 조용히 어긋날 수 있다.
 *
 * | 무엇이 | 어떻게 깨지나 | 여기서 잡는 방법 |
 * |---|---|---|
 * | JVM 시그니처 | non-null `Long` 이 primitive `long` 이 되어 descriptor 가 바뀜 | 리플렉션으로 파라미터·반환 타입 확인 |
 * | 공개 표면 | 원본에 없던 public 생성자·setter·`equals`/`hashCode` 가 생김 | 선언 멤버 전수 확인 |
 * | JPA 매핑 | 테이블·컬럼·fetch·enum 저장 방식이 바뀌어 스키마가 어긋남 | 어노테이션 값 직접 확인 |
 *
 * 실제로 이 테스트가 전환 중 `RefreshTokenJpaEntityRepository.findByMemberId(Long)` 이
 * `findByMemberId(long)` 으로 바뀐 회귀를 잡았다.
 *
 * 컨텍스트 부팅·실제 저장/조회는 [AuthPersistenceMappingTest] 가 담당한다 —
 * 실패 원인이 "표면이 바뀜"인지 "매핑이 안 붙음"인지 구분되도록 파일을 나눴다.
 */
class AuthPersistenceJvmSurfaceTest {
    @Nested
    @DisplayName("entity — 상속·생성자·getter 표면")
    inner class EntitySurface {
        @Test
        fun `세 entity 모두 BaseTimeEntity 를 상속한다`() {
            assertThat(EmailVerification::class.java.superclass).isEqualTo(BaseTimeEntity::class.java)
            assertThat(MemberSocialAccount::class.java.superclass).isEqualTo(BaseTimeEntity::class.java)
            assertThat(RefreshToken::class.java.superclass).isEqualTo(BaseTimeEntity::class.java)
        }

        /** Hibernate 지연 로딩 프록시는 엔티티를 상속해 만든다. final 이면 프록시 생성이 실패한다. */
        @Test
        fun `세 entity 모두 final 이 아니다 - 지연 로딩 프록시 대상`() {
            for (type in ENTITIES) {
                assertThat(Modifier.isFinal(type.modifiers))
                    .describedAs("%s 가 final 이면 Hibernate 프록시를 만들 수 없다", type.simpleName)
                    .isFalse()
            }
        }

        @Test
        fun `무인자 생성자는 protected 하나뿐이다 - JPA 인스턴스화용`() {
            for (type in ENTITIES) {
                val noArg = type.getDeclaredConstructor()
                assertThat(Modifier.isProtected(noArg.modifiers))
                    .describedAs("%s 의 무인자 생성자", type.simpleName)
                    .isTrue()
            }
        }

        /**
         * 원본 Java 는 값 생성자가 전부 `private` 이고 정적 팩토리만 공개했다.
         * Kotlin 이 넣는 `DefaultConstructorMarker` 생성자는 `ACC_SYNTHETIC` 이라 Java 소스에서 호출할 수 없으므로
         * 공개 표면 확대가 아니다 — 그래서 synthetic 을 제외하고 센다.
         */
        @Test
        fun `호출 가능한 public 생성자가 없다`() {
            for (type in ENTITIES) {
                val callablePublic =
                    type.declaredConstructors
                        .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                assertThat(callablePublic)
                    .describedAs("%s 의 호출 가능한 public 생성자", type.simpleName)
                    .isEmpty()
            }
        }

        @Test
        fun `원본에 없던 equals hashCode toString 이 생기지 않았다`() {
            for (type in ENTITIES) {
                val declared = type.declaredMethods.map { it.name }
                assertThat(declared)
                    .describedAs("%s", type.simpleName)
                    .doesNotContain("equals", "hashCode", "toString")
            }
        }

        @Test
        fun `public setter 가 없다 - 상태 변경은 비즈니스 메서드로만 한다`() {
            for (type in ENTITIES) {
                val publicSetters =
                    type.declaredMethods
                        .filter { it.name.startsWith("set") && Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                        .map { it.name }
                assertThat(publicSetters).describedAs("%s", type.simpleName).isEmpty()
            }
        }

        @Test
        fun `식별자 getter 는 참조형 Long 을 반환한다 - primitive 로 바뀌면 언박싱 NPE 위험`() {
            for (type in ENTITIES) {
                assertThat(type.getMethod("getId").returnType)
                    .describedAs("%s.getId()", type.simpleName)
                    .isEqualTo(java.lang.Long::class.java)
            }
        }
    }

    @Nested
    @DisplayName("EmailVerification — 프로퍼티 이름과 getter 이름이 다른 유일한 자리")
    inner class EmailVerificationSurface {
        /** Java 테스트(`EmailVerificationServiceTest`)가 `isVerified()` 를 그대로 호출한다. */
        @Test
        fun `boolean getter 이름이 isVerified 로 유지된다`() {
            val getter = EmailVerification::class.java.getMethod("isVerified")
            assertThat(getter.returnType).isEqualTo(Boolean::class.javaPrimitiveType)
            assertThat(EmailVerification::class.java.declaredMethods.map { it.name })
                .doesNotContain("getVerified")
        }

        /** Spring Data 파생 쿼리 `existsByEmailAndVerifiedTrue` 가 속성명 `verified` 로 해석된다. */
        @Test
        fun `필드 이름은 verified 로 유지된다 - 파생 쿼리가 여기에 묶여 있다`() {
            val field = EmailVerification::class.java.getDeclaredField("verified")
            assertThat(field.type).isEqualTo(Boolean::class.javaPrimitiveType)
        }

        @Test
        fun `정적 팩토리 verified 가 public static 으로 남아 있다`() {
            val factory =
                EmailVerification::class.java.getMethod(
                    "verified",
                    String::class.java,
                    LocalDateTime::class.java,
                )
            assertThat(Modifier.isStatic(factory.modifiers)).isTrue()
            assertThat(Modifier.isPublic(factory.modifiers)).isTrue()
        }

        @Test
        fun `verify 와 unverify 비즈니스 메서드가 유지된다`() {
            val names = EmailVerification::class.java.declaredMethods.map { it.name }
            assertThat(names).contains("verify", "unverify")
        }
    }

    @Nested
    @DisplayName("entity — JPA 매핑 어노테이션과 DB 계약")
    inner class JpaMapping {
        @Test
        fun `테이블 이름이 유지된다`() {
            assertThat(EmailVerification::class.java.getAnnotation(Table::class.java).name)
                .isEqualTo("email_verifications")
            assertThat(MemberSocialAccount::class.java.getAnnotation(Table::class.java).name)
                .isEqualTo("member_social_accounts")
            assertThat(RefreshToken::class.java.getAnnotation(Table::class.java).name)
                .isEqualTo("refresh_tokens")
        }

        @Test
        fun `세 entity 모두 Entity 어노테이션을 유지한다`() {
            for (type in ENTITIES) {
                assertThat(type.getAnnotation(Entity::class.java))
                    .describedAs("%s", type.simpleName)
                    .isNotNull()
            }
        }

        @Test
        fun `식별자는 IDENTITY 전략을 유지한다`() {
            for (type in ENTITIES) {
                val idField = type.getDeclaredField("id")
                assertThat(idField.getAnnotation(Id::class.java)).describedAs("%s.id", type.simpleName).isNotNull()
                assertThat(idField.getAnnotation(GeneratedValue::class.java).strategy)
                    .isEqualTo(GenerationType.IDENTITY)
            }
        }

        @Test
        fun `RefreshToken 컬럼 계약이 유지된다`() {
            val memberId = RefreshToken::class.java.getDeclaredField("memberId").getAnnotation(Column::class.java)
            assertThat(memberId.name).isEqualTo("member_id")
            assertThat(memberId.nullable).isFalse()
            assertThat(memberId.unique).isTrue()

            val token = RefreshToken::class.java.getDeclaredField("token").getAnnotation(Column::class.java)
            assertThat(token.nullable).isFalse()
            assertThat(token.length).isEqualTo(512)

            val expiresAt = RefreshToken::class.java.getDeclaredField("expiresAt").getAnnotation(Column::class.java)
            assertThat(expiresAt.name).isEqualTo("expires_at")
            assertThat(expiresAt.nullable).isFalse()
        }

        @Test
        fun `EmailVerification 컬럼 계약이 유지된다`() {
            val email = EmailVerification::class.java.getDeclaredField("email").getAnnotation(Column::class.java)
            assertThat(email.nullable).isFalse()
            assertThat(email.unique).isTrue()
            assertThat(email.length).isEqualTo(100)

            val verified = EmailVerification::class.java.getDeclaredField("verified").getAnnotation(Column::class.java)
            assertThat(verified.nullable).isFalse()
            assertThat(verified.name).describedAs("컬럼 이름을 비워 필드명 verified 를 그대로 쓴다").isEmpty()

            val verifiedAt =
                EmailVerification::class.java.getDeclaredField("verifiedAt").getAnnotation(Column::class.java)
            assertThat(verifiedAt.name).isEqualTo("verified_at")
        }

        @Test
        fun `MemberSocialAccount 연관관계와 enum 저장 방식이 유지된다`() {
            val member = MemberSocialAccount::class.java.getDeclaredField("member")
            assertThat(member.getAnnotation(ManyToOne::class.java).fetch).isEqualTo(FetchType.LAZY)
            val joinColumn = member.getAnnotation(JoinColumn::class.java)
            assertThat(joinColumn.name).isEqualTo("member_id")
            assertThat(joinColumn.nullable).isFalse()

            val provider = MemberSocialAccount::class.java.getDeclaredField("provider")
            assertThat(provider.getAnnotation(Enumerated::class.java).value).isEqualTo(EnumType.STRING)
            assertThat(provider.getAnnotation(Column::class.java).length).isEqualTo(20)

            val providerUserId =
                MemberSocialAccount::class.java.getDeclaredField("providerUserId").getAnnotation(Column::class.java)
            assertThat(providerUserId.name).isEqualTo("provider_user_id")
            assertThat(providerUserId.length).isEqualTo(255)
        }

        @Test
        fun `MemberSocialAccount unique 제약 두 개가 이름까지 유지된다`() {
            val constraints = MemberSocialAccount::class.java.getAnnotation(Table::class.java).uniqueConstraints
            val byName = constraints.associateBy { it.name }
            assertThat(byName.keys).containsExactlyInAnyOrder(
                "uk_social_account_provider_provider_user_id",
                "uk_social_account_member_provider",
            )
            assertThat(byName.getValue("uk_social_account_provider_provider_user_id").columnNames)
                .containsExactly("provider", "provider_user_id")
            assertThat(byName.getValue("uk_social_account_member_provider").columnNames)
                .containsExactly("member_id", "provider")
        }

        /** LAZY 연관의 getter 가 final 이면 프록시가 값을 채워 넣지 못한다. */
        @Test
        fun `LAZY 연관 getter 가 final 이 아니다`() {
            val getter = MemberSocialAccount::class.java.getMethod("getMember")
            assertThat(Modifier.isFinal(getter.modifiers)).isFalse()
        }
    }

    @Nested
    @DisplayName("repository — Spring Data 계약")
    inner class RepositoryContract {
        @Test
        fun `JPA repository 세 개가 JpaRepository 를 상속한다`() {
            assertThat(JpaRepository::class.java).isAssignableFrom(EmailVerificationRepository::class.java)
            assertThat(JpaRepository::class.java).isAssignableFrom(MemberSocialAccountRepository::class.java)
            assertThat(JpaRepository::class.java).isAssignableFrom(RefreshTokenJpaEntityRepository::class.java)
        }

        @Test
        fun `entity 와 ID 제네릭 타입이 유지된다`() {
            assertThat(genericArgumentsOf(EmailVerificationRepository::class.java))
                .containsExactly(EmailVerification::class.java, java.lang.Long::class.java)
            assertThat(genericArgumentsOf(MemberSocialAccountRepository::class.java))
                .containsExactly(MemberSocialAccount::class.java, java.lang.Long::class.java)
            assertThat(genericArgumentsOf(RefreshTokenJpaEntityRepository::class.java))
                .containsExactly(RefreshToken::class.java, java.lang.Long::class.java)
        }

        /** 전환 중 실제로 깨졌던 자리 — non-null `Long` 은 primitive `long` 이 된다. */
        @Test
        fun `RefreshTokenJpaEntityRepository 파라미터가 참조형 Long 으로 유지된다`() {
            for (name in listOf("findByMemberId", "deleteByMemberId")) {
                val method = RefreshTokenJpaEntityRepository::class.java.getDeclaredMethod(name, java.lang.Long::class.java)
                assertThat(method.parameterTypes[0])
                    .describedAs("%s 파라미터", name)
                    .isEqualTo(java.lang.Long::class.java)
            }
        }

        @Test
        fun `Optional 반환 계약이 유지된다`() {
            assertThat(
                EmailVerificationRepository::class.java
                    .getDeclaredMethod("findByEmail", String::class.java)
                    .returnType,
            ).isEqualTo(Optional::class.java)
            assertThat(
                RefreshTokenJpaEntityRepository::class.java
                    .getDeclaredMethod("findByMemberId", java.lang.Long::class.java)
                    .returnType,
            ).isEqualTo(Optional::class.java)
        }

        @Test
        fun `파생 쿼리 existsByEmailAndVerifiedTrue 가 primitive boolean 을 반환한다`() {
            val method =
                EmailVerificationRepository::class.java
                    .getDeclaredMethod("existsByEmailAndVerifiedTrue", String::class.java)
            assertThat(method.returnType).isEqualTo(Boolean::class.javaPrimitiveType)
        }

        @Test
        fun `fetch join JPQL 과 Param 이름이 한 글자도 바뀌지 않았다`() {
            val method =
                MemberSocialAccountRepository::class.java.getDeclaredMethod(
                    "findByProviderAndProviderUserIdFetchMember",
                    OAuthProvider::class.java,
                    String::class.java,
                )
            assertThat(method.getAnnotation(Query::class.java).value).isEqualTo(
                "select msa from MemberSocialAccount msa join fetch msa.member " +
                    "where msa.provider = :provider and msa.providerUserId = :providerUserId",
            )
            val paramNames = method.parameters.map { it.getAnnotation(Param::class.java)?.value }
            assertThat(paramNames).containsExactly("provider", "providerUserId")
        }

        @Test
        fun `JpaRefreshTokenRepository 가 추상화 구현과 test 프로파일 제한을 유지한다`() {
            assertThat(
                com.dongnemarket.auth.repository.RefreshTokenRepository::class.java,
            ).isAssignableFrom(JpaRefreshTokenRepository::class.java)
            val profile = JpaRefreshTokenRepository::class.java.getAnnotation(org.springframework.context.annotation.Profile::class.java)
            assertThat(profile.value).containsExactly("test")
        }
    }

    companion object {
        private val ENTITIES =
            listOf(
                EmailVerification::class.java,
                MemberSocialAccount::class.java,
                RefreshToken::class.java,
            )

        private fun genericArgumentsOf(repository: Class<*>): List<Class<*>> {
            val jpaRepositoryType =
                repository.genericInterfaces
                    .filterIsInstance<java.lang.reflect.ParameterizedType>()
                    .first { it.rawType == JpaRepository::class.java }
            return jpaRepositoryType.actualTypeArguments.map { it as Class<*> }
        }
    }
}
