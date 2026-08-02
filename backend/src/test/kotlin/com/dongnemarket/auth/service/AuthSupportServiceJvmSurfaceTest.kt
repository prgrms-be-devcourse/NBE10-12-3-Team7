package com.dongnemarket.auth.service

import com.dongnemarket.auth.mail.EmailSender
import com.dongnemarket.auth.mail.SmtpEmailSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.lang.reflect.Modifier

/**
 * 5단계로 옮긴 지원 서비스 4개 + mail 구현 1개의 **JVM 표면과 Spring 계약**을 고정한다.
 *
 * 이 계층은 아직 Java 인 `AuthController`·`AuthService` 가 직접 주입받아 쓰기 때문에 다음이 조용히 어긋날 수 있다.
 *
 * | 무엇이 | 어떻게 깨지나 |
 * |---|---|
 * | 클래스 finality | Kotlin 기본 `final` 이면 `@Transactional` CGLIB 프록시를 만들 수 없어 트랜잭션이 안 걸린다 |
 * | `@Transactional` 위치 | 클래스 레벨과 메서드 레벨(readOnly)이 뒤바뀌면 읽기 전용 경계가 사라진다 |
 * | 참조형 파라미터 | non-null 로 조이면 `Long` 이 primitive 가 되어 descriptor 가 바뀐다 |
 *
 * 동작 계약(호출 순서·예외·메일 내용)은 [AuthSupportServiceContractTest] 가 담당한다.
 */
class AuthSupportServiceJvmSurfaceTest {
    @Nested
    @DisplayName("Spring stereotype 과 프록시 가능성")
    inner class SpringSurface {
        @Test
        fun `네 지원 서비스가 모두 Service 빈이다`() {
            for (type in SERVICES) {
                assertThat(type.getAnnotation(Service::class.java))
                    .describedAs("%s 에 @Service", type.simpleName)
                    .isNotNull()
            }
        }

        @Test
        fun `SmtpEmailSender 는 Component 이고 EmailSender 를 구현한다`() {
            assertThat(SmtpEmailSender::class.java.getAnnotation(Component::class.java)).isNotNull()
            assertThat(EmailSender::class.java).isAssignableFrom(SmtpEmailSender::class.java)
        }

        /** allOpen(kotlin-spring)이 적용되지 않으면 `@Transactional` 프록시가 만들어지지 않는다. */
        @Test
        fun `트랜잭션 대상 클래스가 final 이 아니다`() {
            for (type in listOf(
                RefreshTokenService::class.java,
                EmailVerificationService::class.java,
                PasswordResetService::class.java,
            )) {
                assertThat(Modifier.isFinal(type.modifiers))
                    .describedAs("%s 가 final 이면 CGLIB 프록시를 만들 수 없다", type.simpleName)
                    .isFalse()
            }
        }

        @Test
        fun `public 메서드도 final 이 아니다 - 프록시가 오버라이드해야 한다`() {
            val validate = RefreshTokenService::class.java.getMethod("validateAndGetMemberId", String::class.java)
            assertThat(Modifier.isFinal(validate.modifiers)).isFalse()
        }
    }

    @Nested
    @DisplayName("@Transactional 위치와 옵션")
    inner class TransactionSurface {
        @Test
        fun `RefreshTokenService 는 클래스 레벨 Transactional 을 가진다`() {
            assertThat(RefreshTokenService::class.java.getAnnotation(Transactional::class.java)).isNotNull()
        }

        /** 원본에서 이 메서드만 readOnly 였다 — 위치가 바뀌면 읽기 전용 경계가 사라진다. */
        @Test
        fun `validateAndGetMemberId 만 readOnly 다`() {
            val validate = RefreshTokenService::class.java.getMethod("validateAndGetMemberId", String::class.java)
            assertThat(validate.getAnnotation(Transactional::class.java)?.readOnly).isTrue()

            val save = RefreshTokenService::class.java.getMethod("saveOrReplace", java.lang.Long::class.java, String::class.java)
            assertThat(save.getAnnotation(Transactional::class.java)).describedAs("메서드 레벨 없음").isNull()
        }

        @Test
        fun `EmailVerificationService 와 PasswordResetService 는 클래스 레벨 Transactional 이다`() {
            assertThat(EmailVerificationService::class.java.getAnnotation(Transactional::class.java)).isNotNull()
            assertThat(PasswordResetService::class.java.getAnnotation(Transactional::class.java)).isNotNull()
        }

        /** 원본에 `@Transactional` 이 없었다 — 저장소가 Redis/InMemory 라 새 트랜잭션 경계를 만들지 않는다. */
        @Test
        fun `LoginAttemptService 에는 Transactional 이 없다`() {
            assertThat(LoginAttemptService::class.java.getAnnotation(Transactional::class.java)).isNull()
        }
    }

    @Nested
    @DisplayName("메서드 시그니처")
    inner class MethodSurface {
        @Test
        fun `참조형 파라미터가 primitive 로 축소되지 않았다`() {
            assertThat(
                RefreshTokenService::class.java
                    .getMethod("saveOrReplace", java.lang.Long::class.java, String::class.java),
            ).isNotNull()
            assertThat(
                RefreshTokenService::class.java.getMethod("deleteByMemberId", java.lang.Long::class.java),
            ).isNotNull()
        }

        @Test
        fun `validateAndGetMemberId 는 참조형 Long 을 반환한다`() {
            val validate = RefreshTokenService::class.java.getMethod("validateAndGetMemberId", String::class.java)
            assertThat(validate.returnType).isEqualTo(java.lang.Long::class.java)
        }

        @Test
        fun `public 메서드 집합이 전환 전과 같다`() {
            assertThat(publicMethodNames(LoginAttemptService::class.java))
                .containsExactlyInAnyOrder("assertNotBlocked", "recordFailure", "recordSuccess")
            assertThat(publicMethodNames(RefreshTokenService::class.java))
                .containsExactlyInAnyOrder("saveOrReplace", "validateAndGetMemberId", "deleteByMemberId")
            assertThat(publicMethodNames(EmailVerificationService::class.java))
                .containsExactlyInAnyOrder("requestVerification", "confirmVerification")
            assertThat(publicMethodNames(PasswordResetService::class.java))
                .containsExactlyInAnyOrder("requestReset", "confirmReset")
            assertThat(publicMethodNames(SmtpEmailSender::class.java)).containsExactlyInAnyOrder("send")
        }

        @Test
        fun `생성자가 하나뿐이고 파라미터 타입이 유지된다`() {
            for (type in SERVICES + listOf(SmtpEmailSender::class.java)) {
                val constructors = type.declaredConstructors.filter { !it.isSynthetic }
                assertThat(constructors).describedAs("%s 의 생성자", type.simpleName).hasSize(1)
                assertThat(Modifier.isPublic(constructors[0].modifiers)).isTrue()
            }
            assertThat(
                PasswordResetService::class.java.declaredConstructors
                    .first { !it.isSynthetic }
                    .parameterCount,
            ).isEqualTo(6)
        }

        @Test
        fun `원본에 없던 equals hashCode toString 이 생기지 않았다`() {
            for (type in SERVICES + listOf(SmtpEmailSender::class.java)) {
                assertThat(type.declaredMethods.map { it.name })
                    .describedAs("%s", type.simpleName)
                    .doesNotContain("equals", "hashCode", "toString")
            }
        }
    }

    companion object {
        private val SERVICES =
            listOf(
                LoginAttemptService::class.java,
                RefreshTokenService::class.java,
                EmailVerificationService::class.java,
                PasswordResetService::class.java,
            )

        private fun publicMethodNames(type: Class<*>): List<String> =
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
                .map { it.name }
    }
}
