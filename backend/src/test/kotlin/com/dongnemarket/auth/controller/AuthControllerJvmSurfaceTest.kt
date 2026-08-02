package com.dongnemarket.auth.controller

import com.dongnemarket.auth.dto.LoginRequest
import com.dongnemarket.auth.dto.OAuthLoginRequest
import com.dongnemarket.auth.dto.SignupRequest
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Modifier

/**
 * 7단계로 옮긴 controller 3개의 **JVM 표면**을 고정한다.
 *
 * controller 는 Spring MVC 가 리플렉션으로 endpoint 를 해석하므로, 다음이 조용히 어긋날 수 있다.
 *
 * | 무엇이 | 어떻게 깨지나 |
 * |---|---|
 * | parameter annotation | use-site 를 잘못 쓰면 getter/field 로 이동해 binding 이 사라진다 |
 * | 참조형/primitive | `logout` 의 boxed `Long` 이 primitive 가 되면 principal null 주입이 깨진다 |
 * | 신규 overload | `@JvmOverloads`·default argument 는 mapping 중복을 만든다 |
 *
 * 실제 mapping 결과는 [AuthControllerRequestMappingContractTest] 가 검증한다.
 */
class AuthControllerJvmSurfaceTest {
    @Nested
    @DisplayName("클래스 표면")
    inner class ClassSurface {
        @Test
        fun `세 controller 모두 RestController 이고 Tag 가 유지된다`() {
            for (type in CONTROLLERS) {
                assertThat(type.getAnnotation(RestController::class.java)).describedAs("%s", type.simpleName).isNotNull()
                val tag = type.getAnnotation(Tag::class.java)
                assertThat(tag?.name).isEqualTo("Auth")
                assertThat(tag?.description).isEqualTo("인증 API")
            }
        }

        @Test
        fun `class-level RequestMapping 경로가 유지된다`() {
            assertThat(AuthController::class.java.getAnnotation(RequestMapping::class.java).value)
                .containsExactly("/api/auth")
            assertThat(EmailVerificationController::class.java.getAnnotation(RequestMapping::class.java).value)
                .containsExactly("/api/auth/email-verifications")
            assertThat(PasswordResetController::class.java.getAnnotation(RequestMapping::class.java).value)
                .containsExactly("/api/auth/password-resets")
        }

        /** 원본 생성자: (AuthService, KakaoOAuthClient, GoogleOAuthClient, long, boolean, long) — `@Value` 2·3번째는 primitive. */
        @Test
        fun `AuthController 생성자가 하나뿐이고 파라미터 6개와 primitive 가 유지된다`() {
            val constructors = AuthController::class.java.declaredConstructors.filter { !it.isSynthetic }
            assertThat(constructors).hasSize(1)
            assertThat(Modifier.isPublic(constructors[0].modifiers)).isTrue()
            val types = constructors[0].parameterTypes
            assertThat(types).hasSize(6)
            assertThat(types[3]).isEqualTo(Long::class.javaPrimitiveType)
            assertThat(types[4]).isEqualTo(Boolean::class.javaPrimitiveType)
            assertThat(types[5]).isEqualTo(Long::class.javaPrimitiveType)
        }

        @Test
        fun `단순 controller 2개의 생성자도 하나씩이다`() {
            for (type in listOf(EmailVerificationController::class.java, PasswordResetController::class.java)) {
                val constructors = type.declaredConstructors.filter { !it.isSynthetic }
                assertThat(constructors).describedAs("%s", type.simpleName).hasSize(1)
                assertThat(constructors[0].parameterCount).isEqualTo(1)
            }
        }
    }

    @Nested
    @DisplayName("endpoint 메서드 표면")
    inner class MethodSurface {
        @Test
        fun `public 메서드 집합이 endpoint 12개와 정확히 일치한다 - 신규 overload 없음`() {
            assertThat(publicMethodNames(AuthController::class.java))
                .containsExactlyInAnyOrder(
                    "signup",
                    "login",
                    "reissue",
                    "logout",
                    "kakaoAuthorization",
                    "googleAuthorization",
                    "kakaoLogin",
                    "googleLogin",
                )
            assertThat(publicMethodNames(EmailVerificationController::class.java))
                .containsExactlyInAnyOrder("requestVerification", "confirmVerification")
            assertThat(publicMethodNames(PasswordResetController::class.java))
                .containsExactlyInAnyOrder("requestReset", "confirmReset")
        }

        /** `@JvmOverloads`·default argument 가 있으면 같은 이름의 public 메서드가 2개 이상 생긴다. */
        @Test
        fun `endpoint 이름별 메서드가 하나씩만 존재한다`() {
            for (type in CONTROLLERS) {
                val names = publicMethodNames(type)
                assertThat(names).describedAs("%s 중복", type.simpleName).doesNotHaveDuplicates()
            }
        }

        @Test
        fun `reissue 는 String 파라미터 - logout 은 boxed Long 파라미터다`() {
            val reissue =
                AuthController::class.java.getMethod("reissue", String::class.java, HttpServletResponse::class.java)
            assertThat(reissue.parameterTypes[0]).isEqualTo(String::class.java)
            val logout =
                AuthController::class.java
                    .getMethod("logout", java.lang.Long::class.java, HttpServletResponse::class.java)
            assertThat(logout.parameterTypes[0]).isEqualTo(java.lang.Long::class.java)
        }

        /** 응답 제네릭 — 특히 PasswordReset 의 `Void?` 가 Java 원본과 같은 `ApiResponse<Void>` 로 소거되는지. */
        @Test
        fun `제네릭 반환 타입이 원본과 같다`() {
            val requestReset =
                PasswordResetController::class.java.getMethod("requestReset", com.dongnemarket.auth.dto.PasswordResetRequest::class.java)
            assertThat(requestReset.genericReturnType.toString())
                .isEqualTo(
                    "org.springframework.http.ResponseEntity<com.dongnemarket.global.response.ApiResponse<java.lang.Void>>",
                )
            val login = AuthController::class.java.getMethod("login", LoginRequest::class.java, HttpServletResponse::class.java)
            assertThat(login.genericReturnType.toString())
                .isEqualTo(
                    "org.springframework.http.ResponseEntity<com.dongnemarket.global.response.ApiResponse" +
                        "<com.dongnemarket.auth.dto.AccessTokenResponse>>",
                )
        }
    }

    @Nested
    @DisplayName("parameter annotation — binding 이 파라미터에 실제로 붙어 있다")
    inner class ParameterAnnotations {
        @Test
        fun `signup 의 request 파라미터에 Valid 와 RequestBody 가 붙어 있다`() {
            val method =
                AuthController::class.java.getMethod("signup", SignupRequest::class.java, HttpServletRequest::class.java)
            val annotations = method.parameters[0].annotations.map { it.annotationClass }
            assertThat(annotations).contains(Valid::class, RequestBody::class)
        }

        @Test
        fun `reissue 의 cookie 파라미터가 refreshToken 이름과 required=false 를 유지한다`() {
            val method =
                AuthController::class.java.getMethod("reissue", String::class.java, HttpServletResponse::class.java)
            val cookieValue = method.parameters[0].getAnnotation(CookieValue::class.java)
            assertThat(cookieValue).isNotNull()
            assertThat(cookieValue.name).isEqualTo("refreshToken")
            assertThat(cookieValue.required).isFalse()
        }

        @Test
        fun `logout 의 principal 파라미터에 AuthenticationPrincipal 이 붙어 있다`() {
            val method =
                AuthController::class.java
                    .getMethod("logout", java.lang.Long::class.java, HttpServletResponse::class.java)
            assertThat(method.parameters[0].getAnnotation(AuthenticationPrincipal::class.java)).isNotNull()
        }

        @Test
        fun `OAuth login 의 request 파라미터에 Valid 와 RequestBody 가 붙어 있다`() {
            for (name in listOf("kakaoLogin", "googleLogin")) {
                val method =
                    AuthController::class.java.getMethod(
                        name,
                        OAuthLoginRequest::class.java,
                        HttpServletRequest::class.java,
                        HttpServletResponse::class.java,
                    )
                val annotations = method.parameters[0].annotations.map { it.annotationClass }
                assertThat(annotations).describedAs(name).contains(Valid::class, RequestBody::class)
            }
        }
    }

    companion object {
        private val CONTROLLERS =
            listOf(
                AuthController::class.java,
                EmailVerificationController::class.java,
                PasswordResetController::class.java,
            )

        private fun publicMethodNames(type: Class<*>): List<String> =
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
                .map { it.name }
    }
}
