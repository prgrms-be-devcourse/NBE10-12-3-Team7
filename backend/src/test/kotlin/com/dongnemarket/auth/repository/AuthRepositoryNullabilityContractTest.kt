package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.OAuthProvider
import com.dongnemarket.auth.entity.RefreshToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.valueParameters

/**
 * 2단계에서 옮긴 auth repository 의 **Kotlin nullability 계약**을 고정한다.
 *
 * 왜 별도 파일인가 — 이 회귀는 **`javap` descriptor 비교로 잡히지 않는다.**
 * Kotlin nullability 는 descriptor 가 아니라 `@Metadata` 에 실리기 때문에,
 * `String` 과 `String?` 은 둘 다 `(Ljava/lang/String;)` 로 컴파일된다.
 * 실제로 2단계에서 `existsByEmailAndVerifiedTrue(email: String)` 로 좁혀 놓은 것을
 * `javap` 비교도 Java 호출부 컴파일도 잡지 못했고, 5단계에서 서비스의 null 경로를 복원하다가
 * **Kotlin 호출부가 `!!` 를 강제받아 실패 위치가 앞당겨지는** 형태로 드러났다.
 *
 * 원칙 — **원본 Java 가 참조형이면 Kotlin 에서도 nullable 로 둔다.**
 * 원본이 primitive(`long`·`int`·`boolean`)였던 자리는 반대로 non-null 이어야 한다.
 * 그래서 "전부 nullable" 이 아니라 원본 구분을 그대로 검증한다.
 */
class AuthRepositoryNullabilityContractTest {
    @Nested
    @DisplayName("원본 Java 참조형 파라미터는 Kotlin 에서도 nullable 이다")
    inner class ReferenceParametersStayNullable {
        @Test
        fun `EmailVerificationRepository 의 email 파라미터가 nullable 이다`() {
            assertThat(isNullableParam(EmailVerificationRepository::class, "findByEmail", 0)).isTrue()
            assertThat(isNullableParam(EmailVerificationRepository::class, "existsByEmailAndVerifiedTrue", 0)).isTrue()
        }

        @Test
        fun `MemberSocialAccountRepository 의 provider 와 providerUserId 가 nullable 이다`() {
            val name = "findByProviderAndProviderUserIdFetchMember"
            assertThat(isNullableParam(MemberSocialAccountRepository::class, name, 0)).isTrue()
            assertThat(isNullableParam(MemberSocialAccountRepository::class, name, 1)).isTrue()
        }

        @Test
        fun `RefreshTokenJpaEntityRepository 의 memberId 가 nullable 이다`() {
            assertThat(isNullableParam(RefreshTokenJpaEntityRepository::class, "findByMemberId", 0)).isTrue()
            assertThat(isNullableParam(RefreshTokenJpaEntityRepository::class, "deleteByMemberId", 0)).isTrue()
        }

        @Test
        fun `JpaRefreshTokenRepository 의 memberId 가 nullable 이다`() {
            assertThat(isNullableParam(JpaRefreshTokenRepository::class, "findByMemberId", 0)).isTrue()
            assertThat(isNullableParam(JpaRefreshTokenRepository::class, "deleteByMemberId", 0)).isTrue()
        }
    }

    /**
     * 리플렉션만으로는 "Kotlin 소스에서 실제로 호출 가능한가"를 증명하지 못한다.
     * 아래는 **컴파일되는 호출 fixture** 다 — 파라미터가 non-null 로 좁혀지면 이 파일이 컴파일되지 않는다.
     */
    @Nested
    @DisplayName("Kotlin 호출부가 인위적인 !! 없이 nullable 을 전달할 수 있다")
    inner class NullableCallSitesCompile {
        private val nullEmail: String? = null
        private val nullProvider: OAuthProvider? = null
        private val nullMemberId: Long? = null

        @Test
        fun `EmailVerificationRepository 호출이 컴파일된다`() {
            val repository = mock(EmailVerificationRepository::class.java)

            repository.findByEmail(nullEmail)
            repository.existsByEmailAndVerifiedTrue(nullEmail)

            assertThat(repository).isNotNull()
        }

        @Test
        fun `MemberSocialAccountRepository 호출이 컴파일된다`() {
            val repository = mock(MemberSocialAccountRepository::class.java)

            repository.findByProviderAndProviderUserIdFetchMember(nullProvider, nullEmail)

            assertThat(repository).isNotNull()
        }

        @Test
        fun `RefreshTokenJpaEntityRepository 호출이 컴파일된다`() {
            val repository = mock(RefreshTokenJpaEntityRepository::class.java)

            repository.findByMemberId(nullMemberId)
            repository.deleteByMemberId(nullMemberId)

            assertThat(repository).isNotNull()
        }
    }

    @Nested
    @DisplayName("원본이 primitive 였던 자리는 non-null 로 남는다")
    inner class PrimitiveParametersStayNonNull {
        /** 원본 `boolean existsByEmailAndVerifiedTrue(...)` — 반환은 primitive 다. */
        @Test
        fun `existsByEmailAndVerifiedTrue 반환이 primitive boolean 이다`() {
            val method =
                EmailVerificationRepository::class.java
                    .getDeclaredMethod("existsByEmailAndVerifiedTrue", String::class.java)
            assertThat(method.returnType).isEqualTo(Boolean::class.javaPrimitiveType)
        }

        /** 원본 `Optional<...> findByMemberId(Long)` — 파라미터 descriptor 는 참조형 그대로여야 한다. */
        @Test
        fun `findByMemberId 파라미터 descriptor 가 참조형 Long 이다`() {
            val method =
                RefreshTokenJpaEntityRepository::class.java
                    .getDeclaredMethod("findByMemberId", java.lang.Long::class.java)
            assertThat(method.parameterTypes[0]).isEqualTo(java.lang.Long::class.java)
        }
    }

    @Nested
    @DisplayName("RefreshToken save — 추상화와 구현 2개")
    inner class RefreshTokenSaveContract {
        @Test
        fun `추상화와 구현체 모두 save 파라미터가 nullable 이다`() {
            assertThat(isNullableParam(RefreshTokenRepository::class, "save", 0)).isTrue()
            assertThat(isNullableParam(JpaRefreshTokenRepository::class, "save", 0)).isTrue()
            assertThat(isNullableParam(RedisRefreshTokenRepository::class, "save", 0)).isTrue()
        }

        /** 파라미터가 다시 조여지면 이 호출들이 컴파일되지 않는다. */
        @Test
        fun `nullable 값을 인위적인 bang 없이 전달할 수 있다`() {
            val nullToken: RefreshToken? = null
            val abstraction = mock(RefreshTokenRepository::class.java)
            val jpa = mock(JpaRefreshTokenRepository::class.java)
            val redis = mock(RedisRefreshTokenRepository::class.java)

            abstraction.save(nullToken)
            jpa.save(nullToken)
            redis.save(nullToken)

            assertThat(abstraction).isNotNull()
        }

        @Test
        fun `save 의 JVM descriptor 와 오버로드 수가 유지된다`() {
            for (type in listOf(
                RefreshTokenRepository::class.java,
                JpaRefreshTokenRepository::class.java,
                RedisRefreshTokenRepository::class.java,
            )) {
                val saves = type.declaredMethods.filter { it.name == "save" && !it.isSynthetic && !it.isBridge }
                assertThat(saves).describedAs("%s 의 save", type.simpleName).hasSize(1)
                assertThat(saves[0].parameterTypes[0]).isEqualTo(RefreshToken::class.java)
                assertThat(saves[0].returnType).isEqualTo(RefreshToken::class.java)
            }
        }
    }

    private fun isNullableParam(
        type: kotlin.reflect.KClass<*>,
        functionName: String,
        index: Int,
    ): Boolean {
        val function =
            type.declaredFunctions.firstOrNull { it.name == functionName }
                ?: error("$functionName 을 ${type.simpleName} 에서 찾지 못했다")
        return function.valueParameters[index].type.isMarkedNullable
    }
}
