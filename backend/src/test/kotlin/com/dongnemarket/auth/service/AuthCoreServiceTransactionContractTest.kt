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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.lang.reflect.Method

/**
 * 6단계 핵심 서비스의 **트랜잭션 계약**을 원본 Java 표와 동일하게 고정한다.
 *
 * | 대상 | 계약 |
 * |---|---|
 * | `AuthService` 클래스 | `@Transactional(readOnly = true)` |
 * | `signup`·`login`·`reissue`·`logout` | 메서드 `@Transactional` (readOnly 아님, REQUIRED) |
 * | `startAuthorization`·`oauthLogin` | `Propagation.NOT_SUPPORTED` — 트랜잭션을 열지 않는다 |
 * | `OAuthSignupTransaction.signUp` | 메서드 `@Transactional` (신규 가입 원자성) |
 * | `OAuthSignupTransaction.reconcileAfterConflict` | `@Transactional(readOnly = true)` (롤백 후 새 트랜잭션 재조회) |
 *
 * 왜 고정하나 — `oauthLogin` 이 클래스 레벨 readOnly 를 상속받으면 제공자 네트워크 호출이 DB 커넥션을
 * 붙든 채 일어나고, [OAuthSignupTransaction] 의 두 메서드가 **서로 다른 트랜잭션**으로 실행돼야 하는
 * 요구(UNIQUE 위반 롤백 → 새 read-only 재조회)도 깨진다. 어긋나도 정상 흐름 테스트는 통과하기 때문에
 * annotation 자체를 검증한다.
 */
class AuthCoreServiceTransactionContractTest {
    @Nested
    @DisplayName("AuthService 클래스 레벨")
    inner class ClassLevel {
        @Test
        fun `클래스에 readOnly Transactional 이 있다`() {
            val annotation = AuthService::class.java.getAnnotation(Transactional::class.java)
            assertThat(annotation).isNotNull()
            assertThat(annotation.readOnly).isTrue()
        }

        /** 별도 빈이어야 프록시 경유로 두 트랜잭션이 분리된다 — self-invocation 이면 프록시가 못 가로챈다. */
        @Test
        fun `OAuthSignupTransaction 은 AuthService 와 별도 클래스이고 생성자로 주입받는다`() {
            assertThat(OAuthSignupTransaction::class.java).isNotEqualTo(AuthService::class.java)
            val constructor = AuthService::class.java.declaredConstructors.first { !it.isSynthetic }
            assertThat(constructor.parameterTypes).contains(OAuthSignupTransaction::class.java)
        }

        /** 클래스 레벨과 달리 `OAuthSignupTransaction` 은 메서드 레벨만 갖는다 — 두 메서드의 옵션이 다르기 때문. */
        @Test
        fun `OAuthSignupTransaction 클래스 레벨 Transactional 은 없다`() {
            assertThat(OAuthSignupTransaction::class.java.getAnnotation(Transactional::class.java)).isNull()
        }
    }

    @Nested
    @DisplayName("쓰기 메서드 4개 — 메서드 레벨 @Transactional")
    inner class WriteMethods {
        @Test
        fun `signup login reissue logout 은 REQUIRED 쓰기 트랜잭션이다`() {
            for (method in listOf(signup(), login(), reissue(), logout())) {
                val annotation = method.getAnnotation(Transactional::class.java)
                assertThat(annotation).describedAs("%s 의 @Transactional", method.name).isNotNull()
                assertThat(annotation.readOnly).describedAs("%s 는 readOnly 가 아니다", method.name).isFalse()
                assertThat(annotation.propagation)
                    .describedAs("%s 의 propagation", method.name)
                    .isEqualTo(Propagation.REQUIRED)
            }
        }
    }

    @Nested
    @DisplayName("OAuth 흐름 2개 — NOT_SUPPORTED")
    inner class OAuthMethods {
        @Test
        fun `startAuthorization 은 트랜잭션을 열지 않는다`() {
            val annotation = startAuthorization().getAnnotation(Transactional::class.java)
            assertThat(annotation).isNotNull()
            assertThat(annotation.propagation).isEqualTo(Propagation.NOT_SUPPORTED)
        }

        /** 제공자 토큰교환(네트워크)이 DB 커넥션을 붙들지 않아야 하고, signUp 의 REQUIRED 합류도 막아야 한다. */
        @Test
        fun `oauthLogin 은 트랜잭션을 열지 않는다`() {
            val annotation = oauthLogin().getAnnotation(Transactional::class.java)
            assertThat(annotation).isNotNull()
            assertThat(annotation.propagation).isEqualTo(Propagation.NOT_SUPPORTED)
        }
    }

    @Nested
    @DisplayName("OAuthSignupTransaction — 서로 다른 두 트랜잭션")
    inner class SignupTransaction {
        @Test
        fun `signUp 은 쓰기 트랜잭션이다`() {
            val method = OAuthSignupTransaction::class.java.getMethod("signUp", OAuthUserIdentity::class.java)
            val annotation = method.getAnnotation(Transactional::class.java)
            assertThat(annotation).isNotNull()
            assertThat(annotation.readOnly).isFalse()
            assertThat(annotation.propagation).isEqualTo(Propagation.REQUIRED)
        }

        /** signUp 롤백 뒤 **완전히 새로운** read-only 트랜잭션에서 재조회해야 한다. */
        @Test
        fun `reconcileAfterConflict 는 read-only 트랜잭션이다`() {
            val method =
                OAuthSignupTransaction::class.java
                    .getMethod("reconcileAfterConflict", OAuthProvider::class.java, String::class.java)
            val annotation = method.getAnnotation(Transactional::class.java)
            assertThat(annotation).isNotNull()
            assertThat(annotation.readOnly).isTrue()
        }
    }

    companion object {
        private fun signup(): Method =
            AuthService::class.java.getMethod("signup", SignupRequest::class.java, String::class.java, String::class.java)

        private fun login(): Method = AuthService::class.java.getMethod("login", LoginRequest::class.java)

        private fun startAuthorization(): Method =
            AuthService::class.java.getMethod("startAuthorization", OAuthClient::class.java, String::class.java)

        private fun oauthLogin(): Method =
            AuthService::class.java.getMethod(
                "oauthLogin",
                OAuthClient::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            )

        private fun reissue(): Method = AuthService::class.java.getMethod("reissue", String::class.java)

        private fun logout(): Method = AuthService::class.java.getMethod("logout", java.lang.Long::class.java)
    }
}
