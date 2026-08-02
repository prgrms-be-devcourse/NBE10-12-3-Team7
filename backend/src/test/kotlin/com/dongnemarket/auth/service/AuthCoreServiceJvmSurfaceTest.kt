package com.dongnemarket.auth.service

import com.dongnemarket.auth.client.OAuthClient
import com.dongnemarket.auth.client.OAuthUserIdentity
import com.dongnemarket.auth.dto.LoginRequest
import com.dongnemarket.auth.dto.SignupRequest
import com.dongnemarket.auth.entity.OAuthProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.lang.reflect.Modifier
import java.util.Optional

/**
 * 6단계로 옮긴 핵심 서비스 2개([AuthService]·[OAuthSignupTransaction])의 **JVM 표면**을 고정한다.
 *
 * 이 계층은 아직 Java 인 controller 3개가 직접 주입받아 쓰기 때문에 다음이 조용히 어긋날 수 있다.
 *
 * | 무엇이 | 어떻게 깨지나 |
 * |---|---|
 * | 클래스/메서드 finality | Kotlin 기본 `final` 이면 `@Transactional` CGLIB 프록시를 만들 수 없다 |
 * | 참조형 파라미터 | non-null 로 조이면 `Long` 이 primitive 가 되어 descriptor 가 바뀐다 |
 * | 생성자 파라미터 | 순서·타입이 바뀌면 Java 호출부와 수동 생성 테스트가 깨진다 |
 *
 * 트랜잭션 계약은 [AuthCoreServiceTransactionContractTest], 호출 순서는
 * [AuthCoreServiceOrchestrationContractTest], nullability 는 [AuthCoreServiceNullabilityContractTest] 가 담당한다.
 */
class AuthCoreServiceJvmSurfaceTest {
    @Nested
    @DisplayName("Spring stereotype 과 프록시 가능성")
    inner class SpringSurface {
        @Test
        fun `AuthService 는 Service 이고 OAuthSignupTransaction 은 Component 다`() {
            assertThat(AuthService::class.java.getAnnotation(Service::class.java)).isNotNull()
            assertThat(OAuthSignupTransaction::class.java.getAnnotation(Component::class.java)).isNotNull()
        }

        /** allOpen(kotlin-spring)이 적용되지 않으면 `@Transactional` 프록시가 만들어지지 않는다. */
        @Test
        fun `두 클래스 모두 final 이 아니다`() {
            for (type in listOf(AuthService::class.java, OAuthSignupTransaction::class.java)) {
                assertThat(Modifier.isFinal(type.modifiers))
                    .describedAs("%s 가 final 이면 CGLIB 프록시를 만들 수 없다", type.simpleName)
                    .isFalse()
            }
        }

        @Test
        fun `public 메서드도 final 이 아니다 - 프록시가 오버라이드해야 한다`() {
            for (type in listOf(AuthService::class.java, OAuthSignupTransaction::class.java)) {
                val publicMethods =
                    type.declaredMethods.filter {
                        Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge
                    }
                for (method in publicMethods) {
                    assertThat(Modifier.isFinal(method.modifiers))
                        .describedAs("%s.%s", type.simpleName, method.name)
                        .isFalse()
                }
            }
        }
    }

    @Nested
    @DisplayName("메서드 시그니처")
    inner class MethodSurface {
        @Test
        fun `AuthService public 메서드 집합이 전환 전과 같다`() {
            assertThat(publicMethodNames(AuthService::class.java))
                .containsExactlyInAnyOrder("signup", "login", "startAuthorization", "oauthLogin", "reissue", "logout")
        }

        @Test
        fun `OAuthSignupTransaction public 메서드 집합이 전환 전과 같다`() {
            assertThat(publicMethodNames(OAuthSignupTransaction::class.java))
                .containsExactlyInAnyOrder("signUp", "reconcileAfterConflict")
        }

        /** 원본 `void logout(Long memberId)` — 참조형이 primitive 로 축소되면 descriptor 가 바뀐다. */
        @Test
        fun `logout 파라미터가 참조형 Long 이다`() {
            val logout = AuthService::class.java.getMethod("logout", java.lang.Long::class.java)
            assertThat(logout.parameterTypes[0]).isEqualTo(java.lang.Long::class.java)
        }

        @Test
        fun `public 메서드 파라미터 타입이 전환 전과 같다`() {
            assertThat(
                AuthService::class.java
                    .getMethod("signup", SignupRequest::class.java, String::class.java, String::class.java),
            ).isNotNull()
            assertThat(AuthService::class.java.getMethod("login", LoginRequest::class.java)).isNotNull()
            assertThat(
                AuthService::class.java
                    .getMethod("startAuthorization", OAuthClient::class.java, String::class.java),
            ).isNotNull()
            assertThat(
                AuthService::class.java.getMethod(
                    "oauthLogin",
                    OAuthClient::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                ),
            ).isNotNull()
            assertThat(AuthService::class.java.getMethod("reissue", String::class.java)).isNotNull()
        }

        /** 원본 `Optional<Member> reconcileAfterConflict(...)` — Java 호출 관례가 그대로 남는다. */
        @Test
        fun `reconcileAfterConflict 는 Optional 을 반환한다`() {
            val method =
                OAuthSignupTransaction::class.java
                    .getMethod("reconcileAfterConflict", OAuthProvider::class.java, String::class.java)
            assertThat(method.returnType).isEqualTo(Optional::class.java)
        }

        @Test
        fun `signUp 파라미터가 OAuthUserIdentity 하나다`() {
            val method = OAuthSignupTransaction::class.java.getMethod("signUp", OAuthUserIdentity::class.java)
            assertThat(method.parameterCount).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("생성자")
    inner class ConstructorSurface {
        @Test
        fun `AuthService 생성자가 하나뿐이고 파라미터 13개가 유지된다`() {
            val constructors = AuthService::class.java.declaredConstructors.filter { !it.isSynthetic }
            assertThat(constructors).hasSize(1)
            assertThat(Modifier.isPublic(constructors[0].modifiers)).isTrue()
            assertThat(constructors[0].parameterCount).isEqualTo(13)
        }

        /** 원본 `@Value` 2개는 primitive `long`·`int` 였다 — boxed 로 바뀌면 descriptor 가 바뀐다. */
        @Test
        fun `Value 파라미터 2개가 primitive 로 유지된다`() {
            val constructor = AuthService::class.java.declaredConstructors.first { !it.isSynthetic }
            assertThat(constructor.parameterTypes[11]).isEqualTo(Long::class.javaPrimitiveType)
            assertThat(constructor.parameterTypes[12]).isEqualTo(Int::class.javaPrimitiveType)
        }

        @Test
        fun `OAuthSignupTransaction 생성자가 하나뿐이고 파라미터 3개가 유지된다`() {
            val constructors = OAuthSignupTransaction::class.java.declaredConstructors.filter { !it.isSynthetic }
            assertThat(constructors).hasSize(1)
            assertThat(Modifier.isPublic(constructors[0].modifiers)).isTrue()
            assertThat(constructors[0].parameterCount).isEqualTo(3)
        }
    }

    @Nested
    @DisplayName("원본에 없던 표면이 생기지 않았다")
    inner class NoExtraSurface {
        @Test
        fun `equals hashCode toString 이 생기지 않았다`() {
            for (type in listOf(AuthService::class.java, OAuthSignupTransaction::class.java)) {
                assertThat(type.declaredMethods.map { it.name })
                    .describedAs("%s", type.simpleName)
                    .doesNotContain("equals", "hashCode", "toString")
            }
        }
    }

    companion object {
        private fun publicMethodNames(type: Class<*>): List<String> =
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
                .map { it.name }
    }
}
