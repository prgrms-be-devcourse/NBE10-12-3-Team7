package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.OAuthProvider
import com.dongnemarket.auth.entity.RefreshToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository
import java.lang.reflect.Modifier
import java.time.Duration
import java.util.Optional

/**
 * 3단계로 옮긴 저장소 타입 14개(추상화 5 · Redis 5 · InMemory 4)의 **JVM 표면과 Spring 빈 경계**를 고정한다.
 *
 * 이 계층은 아직 Java 인 서비스(`AuthService`·`EmailVerificationService`·`PasswordResetService`
 * ·`LoginAttemptService`·`RefreshTokenService`)가 직접 주입받아 쓰기 때문에, 다음이 조용히 어긋날 수 있다.
 *
 * | 무엇이 | 어떻게 깨지나 |
 * |---|---|
 * | 참조형 파라미터 | non-null 로 조이면 `Long` 이 primitive `long` 이 되어 descriptor 가 바뀐다 |
 * | `Optional` 반환 | Kotlin nullable 로 바꾸면 Java 호출부의 `.orElseThrow()` 가 깨진다 |
 * | 프로파일 경계 | `@Profile` 이 빠지면 test 에서 Redis 구현이, 운영에서 InMemory 구현이 선택된다 |
 *
 * 동작 계약(TTL·consume·덮어쓰기)은 [AuthStoreContractTest] 가, Redis key·TTL 은 기존
 * Testcontainers 통합 테스트가 담당한다 — 실패 원인이 구분되도록 파일을 나눴다.
 */
class AuthStoreJvmSurfaceTest {
    @Nested
    @DisplayName("저장소 추상화 5개 — Java 호출부가 기대하는 시그니처")
    inner class AbstractionSurface {
        @Test
        fun `다섯 추상화가 모두 interface 로 남아 있다`() {
            for (type in ABSTRACTIONS) {
                assertThat(type.isInterface).describedAs("%s", type.simpleName).isTrue()
            }
        }

        @Test
        fun `참조형 파라미터가 primitive 로 축소되지 않았다`() {
            assertThat(
                PasswordResetTokenRepository::class.java
                    .getMethod("save", java.lang.Long::class.java, String::class.java, Duration::class.java),
            ).isNotNull()
            assertThat(
                PasswordResetTokenRepository::class.java
                    .getMethod("findTokenHashByMemberId", java.lang.Long::class.java),
            ).isNotNull()
            assertThat(
                RefreshTokenRepository::class.java.getMethod("findByMemberId", java.lang.Long::class.java),
            ).isNotNull()
            assertThat(
                RefreshTokenRepository::class.java.getMethod("deleteByMemberId", java.lang.Long::class.java),
            ).isNotNull()
        }

        /** 원본이 primitive 였던 자리는 primitive 로 남아야 한다 — 반대 방향 회귀도 막는다. */
        @Test
        fun `원본이 primitive 였던 반환·파라미터는 그대로 primitive 다`() {
            assertThat(
                LoginAttemptRepository::class.java.getMethod("getFailureCount", String::class.java).returnType,
            ).isEqualTo(Long::class.javaPrimitiveType)

            val issue =
                OAuthStateRepository::class.java.getMethod(
                    "issue",
                    String::class.java,
                    OAuthAuthorizationState::class.java,
                    Duration::class.java,
                    Int::class.javaPrimitiveType,
                )
            assertThat(issue.returnType).isEqualTo(Boolean::class.javaPrimitiveType)
            assertThat(issue.parameterTypes[3]).isEqualTo(Int::class.javaPrimitiveType)
        }

        @Test
        fun `Optional 반환 계약이 유지된다`() {
            val optionalReturning =
                listOf(
                    EmailVerificationCodeRepository::class.java.getMethod("findCode", String::class.java),
                    EmailVerificationCodeRepository::class.java.getMethod("getRemainingTtl", String::class.java),
                    PasswordResetTokenRepository::class.java
                        .getMethod("findMemberIdByTokenHash", String::class.java),
                    OAuthStateRepository::class.java.getMethod(
                        "consume",
                        String::class.java,
                        OAuthProvider::class.java,
                        String::class.java,
                    ),
                    RefreshTokenRepository::class.java.getMethod("findByMemberId", java.lang.Long::class.java),
                )
            for (method in optionalReturning) {
                assertThat(method.returnType).describedAs("%s", method.name).isEqualTo(Optional::class.java)
            }
        }

        @Test
        fun `메서드 집합이 전환 전과 같다 - 신규 overload 나 편의 메서드가 없다`() {
            assertThat(publicMethodNames(EmailVerificationCodeRepository::class.java))
                .containsExactlyInAnyOrder("save", "findCode", "getRemainingTtl", "delete")
            assertThat(publicMethodNames(LoginAttemptRepository::class.java))
                .containsExactlyInAnyOrder("getFailureCount", "incrementFailure", "resetFailure")
            assertThat(publicMethodNames(OAuthStateRepository::class.java))
                .containsExactlyInAnyOrder("issue", "consume")
            assertThat(publicMethodNames(PasswordResetTokenRepository::class.java))
                .containsExactlyInAnyOrder(
                    "save",
                    "findTokenHashByMemberId",
                    "getRemainingTtlByMemberId",
                    "findMemberIdByTokenHash",
                    "deleteByMemberId",
                    "deleteByTokenHash",
                )
            assertThat(publicMethodNames(RefreshTokenRepository::class.java))
                .containsExactlyInAnyOrder("findByMemberId", "save", "deleteByMemberId")
        }

        /** 단일 추상 메서드라도 `fun interface` 로 바꾸지 않았다 — Java 호출부에 람다 대입 경로가 새로 생긴다. */
        @Test
        fun `default method 가 새로 생기지 않았다`() {
            for (type in ABSTRACTIONS) {
                val defaults = type.declaredMethods.filter { it.isDefault }.map { it.name }
                assertThat(defaults).describedAs("%s", type.simpleName).isEmpty()
            }
        }
    }

    @Nested
    @DisplayName("구현 9개 — Spring 빈 경계와 생성자")
    inner class ImplementationSurface {
        @Test
        fun `구현체가 각각 올바른 추상화를 구현한다`() {
            assertThat(EmailVerificationCodeRepository::class.java)
                .isAssignableFrom(RedisEmailVerificationCodeRepository::class.java)
                .isAssignableFrom(InMemoryEmailVerificationCodeRepository::class.java)
            assertThat(LoginAttemptRepository::class.java)
                .isAssignableFrom(RedisLoginAttemptRepository::class.java)
                .isAssignableFrom(InMemoryLoginAttemptRepository::class.java)
            assertThat(OAuthStateRepository::class.java)
                .isAssignableFrom(RedisOAuthStateRepository::class.java)
                .isAssignableFrom(InMemoryOAuthStateRepository::class.java)
            assertThat(PasswordResetTokenRepository::class.java)
                .isAssignableFrom(RedisPasswordResetTokenRepository::class.java)
                .isAssignableFrom(InMemoryPasswordResetTokenRepository::class.java)
            assertThat(RefreshTokenRepository::class.java)
                .isAssignableFrom(RedisRefreshTokenRepository::class.java)
                .isAssignableFrom(JpaRefreshTokenRepository::class.java)
        }

        @Test
        fun `모든 구현체가 Repository 빈이다`() {
            for (type in IMPLEMENTATIONS) {
                assertThat(type.getAnnotation(Repository::class.java))
                    .describedAs("%s 에 @Repository", type.simpleName)
                    .isNotNull()
            }
        }

        /** 프로파일이 어긋나면 test 에서 Redis 를, 운영에서 InMemory 를 잡는다. */
        @Test
        fun `프로파일 조건이 전환 전과 같다`() {
            for (type in REDIS_IMPLEMENTATIONS) {
                assertThat(type.getAnnotation(Profile::class.java).value)
                    .describedAs("%s", type.simpleName)
                    .containsExactly("!test")
            }
            for (type in IN_MEMORY_IMPLEMENTATIONS) {
                assertThat(type.getAnnotation(Profile::class.java).value)
                    .describedAs("%s", type.simpleName)
                    .containsExactly("test")
            }
            assertThat(JpaRefreshTokenRepository::class.java.getAnnotation(Profile::class.java).value)
                .containsExactly("test")
        }

        /**
         * [InMemoryLoginAttemptRepository] 만 예외로 2개다 — 계약 테스트가 시간을 통제할 수 있게
         * `Clock` 기본값 생성자를 가지며, Kotlin 이 전 파라미터 기본값 규칙으로 무인자 생성자를
         * 추가 생성한다. 스프링은 `Clock` 빈이 없으면 무인자 쪽으로 폴백해 `systemUTC` 로 뜬다.
         */
        @Test
        fun `구현체는 public 생성자로 주입된다`() {
            for (type in IMPLEMENTATIONS) {
                val constructors = type.declaredConstructors.filter { !it.isSynthetic }
                val expected = if (type == InMemoryLoginAttemptRepository::class.java) 2 else 1
                assertThat(constructors).describedAs("%s 의 생성자", type.simpleName).hasSize(expected)
                for (constructor in constructors) {
                    assertThat(Modifier.isPublic(constructor.modifiers)).isTrue()
                }
            }
        }

        @Test
        fun `InMemoryPasswordResetTokenRepository 의 clear 가 public 으로 남아 있다`() {
            val clear = InMemoryPasswordResetTokenRepository::class.java.getMethod("clear")
            assertThat(Modifier.isPublic(clear.modifiers)).isTrue()
        }

        @Test
        fun `구현체에 equals hashCode toString 이 새로 생기지 않았다`() {
            for (type in IMPLEMENTATIONS) {
                assertThat(type.declaredMethods.map { it.name })
                    .describedAs("%s", type.simpleName)
                    .doesNotContain("equals", "hashCode", "toString")
            }
        }

        @Test
        fun `Redis 구현이 RefreshToken 을 그대로 반환한다 - 반환 타입 축소 없음`() {
            assertThat(
                RedisRefreshTokenRepository::class.java
                    .getMethod("save", RefreshToken::class.java)
                    .returnType,
            ).isEqualTo(RefreshToken::class.java)
        }
    }

    companion object {
        private val ABSTRACTIONS =
            listOf(
                EmailVerificationCodeRepository::class.java,
                LoginAttemptRepository::class.java,
                OAuthStateRepository::class.java,
                PasswordResetTokenRepository::class.java,
                RefreshTokenRepository::class.java,
            )

        private val REDIS_IMPLEMENTATIONS =
            listOf(
                RedisEmailVerificationCodeRepository::class.java,
                RedisLoginAttemptRepository::class.java,
                RedisOAuthStateRepository::class.java,
                RedisPasswordResetTokenRepository::class.java,
                RedisRefreshTokenRepository::class.java,
            )

        private val IN_MEMORY_IMPLEMENTATIONS =
            listOf(
                InMemoryEmailVerificationCodeRepository::class.java,
                InMemoryLoginAttemptRepository::class.java,
                InMemoryOAuthStateRepository::class.java,
                InMemoryPasswordResetTokenRepository::class.java,
            )

        private val IMPLEMENTATIONS = REDIS_IMPLEMENTATIONS + IN_MEMORY_IMPLEMENTATIONS

        private fun publicMethodNames(type: Class<*>): List<String> =
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
                .map { it.name }
    }
}
