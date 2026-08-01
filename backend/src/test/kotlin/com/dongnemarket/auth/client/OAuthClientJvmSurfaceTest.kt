package com.dongnemarket.auth.client

import com.dongnemarket.auth.config.OAuthWebClientConfig
import com.dongnemarket.auth.entity.OAuthProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.lang.reflect.Modifier

/**
 * 4단계로 옮긴 OAuth client 6개 + config 1개의 **JVM 표면과 Spring 빈 경계**를 고정한다.
 *
 * 이 계층은 아직 Java 인 `AuthService` 가 `OAuthClient` 목록을 주입받아 쓰고,
 * `KakaoOAuthClient`/`GoogleOAuthClient` 가 `oauthWebClient` 빈을 **파라미터 이름으로** 지목한다.
 * 그래서 다음이 조용히 어긋날 수 있다.
 *
 * | 무엇이 | 어떻게 깨지나 |
 * |---|---|
 * | `@Bean` 메서드 이름 | Kotlin 함수 이름이 곧 빈 이름 — 바뀌면 클라이언트 2개가 빈을 못 찾는다 |
 * | 참조형 파라미터 | non-null 로 조이면 런타임 null 검사가 삽입돼 Java 호출부 동작이 달라진다 |
 * | primitive 파라미터 | nullable 로 풀면 `int`/`long` 이 박싱 타입이 되어 descriptor 가 바뀐다 |
 *
 * 요청 형식(URL·파라미터 이름)은 [OAuthAuthorizationUrlContractTest] 가 담당한다 —
 * 실패 원인이 구분되도록 파일을 나눴다.
 */
class OAuthClientJvmSurfaceTest {
    @Nested
    @DisplayName("OAuthClient 추상화와 구현 2개")
    inner class ClientSurface {
        @Test
        fun `OAuthClient 는 interface 로 남아 있고 default method 가 없다`() {
            assertThat(OAuthClient::class.java.isInterface).isTrue()
            assertThat(OAuthClient::class.java.declaredMethods.filter { it.isDefault }).isEmpty()
        }

        @Test
        fun `메서드 집합과 시그니처가 전환 전과 같다`() {
            assertThat(OAuthClient::class.java.getMethod("provider").returnType)
                .isEqualTo(OAuthProvider::class.java)
            val resolve =
                OAuthClient::class.java.getMethod(
                    "resolveIdentity",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                )
            assertThat(resolve.returnType).isEqualTo(OAuthUserIdentity::class.java)
            assertThat(OAuthClient::class.java.declaredMethods.map { it.name })
                .containsExactlyInAnyOrder("provider", "resolveIdentity")
        }

        @Test
        fun `카카오 구글 구현체가 OAuthClient 를 구현하고 Component 빈이다`() {
            for (type in listOf(KakaoOAuthClient::class.java, GoogleOAuthClient::class.java)) {
                assertThat(OAuthClient::class.java).isAssignableFrom(type)
                assertThat(type.getAnnotation(Component::class.java))
                    .describedAs("%s 에 @Component", type.simpleName)
                    .isNotNull()
            }
        }

        @Test
        fun `구현체는 public 생성자 하나로 주입된다`() {
            for (type in listOf(
                KakaoOAuthClient::class.java,
                GoogleOAuthClient::class.java,
                GoogleIdTokenValidator::class.java,
                OAuthAuthorizationUrlFactory::class.java,
            )) {
                val constructors = type.declaredConstructors.filter { !it.isSynthetic }
                assertThat(constructors).describedAs("%s 의 생성자", type.simpleName).hasSize(1)
                assertThat(Modifier.isPublic(constructors[0].modifiers)).isTrue()
            }
        }

        /** 원본 Java 생성자의 파라미터 타입·개수를 유지해야 Spring 이 같은 방식으로 주입한다. */
        @Test
        fun `생성자 파라미터 타입이 전환 전과 같다`() {
            val kakao = KakaoOAuthClient::class.java.declaredConstructors.first { !it.isSynthetic }
            assertThat(kakao.parameterTypes).containsExactly(
                WebClient::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
            )

            val google = GoogleOAuthClient::class.java.declaredConstructors.first { !it.isSynthetic }
            assertThat(google.parameterTypes).containsExactly(
                WebClient::class.java,
                GoogleIdTokenValidator::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
            )
        }

        @Test
        fun `provider 가 각각 KAKAO GOOGLE 을 반환하도록 선언돼 있다`() {
            assertThat(KakaoOAuthClient::class.java.getMethod("provider").returnType)
                .isEqualTo(OAuthProvider::class.java)
            assertThat(GoogleOAuthClient::class.java.getMethod("provider").returnType)
                .isEqualTo(OAuthProvider::class.java)
        }

        @Test
        fun `원본에 없던 public 메서드가 늘지 않았다`() {
            assertThat(publicMethodNames(KakaoOAuthClient::class.java))
                .containsExactlyInAnyOrder("provider", "resolveIdentity")
            assertThat(publicMethodNames(GoogleOAuthClient::class.java))
                .containsExactlyInAnyOrder("provider", "resolveIdentity")
            assertThat(publicMethodNames(GoogleIdTokenValidator::class.java))
                .containsExactlyInAnyOrder("validate")
            assertThat(publicMethodNames(OAuthAuthorizationUrlFactory::class.java))
                .containsExactlyInAnyOrder("redirectUri", "build")
        }
    }

    @Nested
    @DisplayName("GoogleIdTokenValidator")
    inner class ValidatorSurface {
        @Test
        fun `validate 시그니처가 유지된다`() {
            val validate =
                GoogleIdTokenValidator::class.java.getMethod("validate", String::class.java, String::class.java)
            assertThat(validate.returnType).isEqualTo(Jwt::class.java)
        }

        @Test
        fun `Component 빈이다`() {
            assertThat(GoogleIdTokenValidator::class.java.getAnnotation(Component::class.java)).isNotNull()
        }
    }

    @Nested
    @DisplayName("OAuthWebClientConfig — 빈 이름이 곧 계약")
    inner class ConfigSurface {
        @Test
        fun `Configuration 클래스이고 무인자 생성자를 가진다`() {
            assertThat(OAuthWebClientConfig::class.java.getAnnotation(Configuration::class.java)).isNotNull()
            assertThat(OAuthWebClientConfig::class.java.getDeclaredConstructor()).isNotNull()
        }

        /** 클라이언트 2개가 파라미터 이름 `oauthWebClient` 로 이 빈을 지목한다. */
        @Test
        fun `Bean 메서드 이름이 oauthWebClient 로 유지된다`() {
            val beanMethods =
                OAuthWebClientConfig::class.java.declaredMethods
                    .filter { it.getAnnotation(Bean::class.java) != null }
            assertThat(beanMethods.map { it.name }).containsExactly("oauthWebClient")
        }

        @Test
        fun `Bean 은 하나뿐이고 WebClient 를 반환한다`() {
            val bean =
                OAuthWebClientConfig::class.java.getMethod(
                    "oauthWebClient",
                    WebClient.Builder::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            assertThat(bean.returnType).isEqualTo(WebClient::class.java)
        }

        /** 기본값 파라미터나 @JvmOverloads 로 오버로드가 늘면 주입 후보가 달라질 수 있다. */
        @Test
        fun `오버로드가 새로 생기지 않았다`() {
            val overloads =
                OAuthWebClientConfig::class.java.declaredMethods.filter { it.name == "oauthWebClient" }
            assertThat(overloads).hasSize(1)
        }
    }

    companion object {
        private fun publicMethodNames(type: Class<*>): List<String> =
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
                .map { it.name }
    }
}
