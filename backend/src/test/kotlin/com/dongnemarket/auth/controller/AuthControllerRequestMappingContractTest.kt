package com.dongnemarket.auth.controller

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * 실제 Spring context 의 [RequestMappingHandlerMapping] 으로 **전환 후 mapping 결과**를 고정한다.
 *
 * 소스에 annotation 을 적었다는 사실만으로는 부족하다 — Kotlin use-site 가 어긋나면 컴파일은 되지만
 * Spring 이 endpoint 를 다르게(또는 아예 안) 등록한다. 그래서 등록된 (HTTP method, full path) 집합
 * 자체를 검증한다. 전환 전 Java 상태의 동일 snapshot 과 비교해 차이 0 을 확인했다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthControllerRequestMappingContractTest {
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    lateinit var context: ApplicationContext

    private fun authMappings(): List<Triple<String, String, String>> =
        handlerMapping.handlerMethods.entries
            .filter { it.value.beanType.packageName == "com.dongnemarket.auth.controller" }
            .flatMap { (info, method) ->
                info.methodsCondition.methods.flatMap { httpMethod ->
                    (info.pathPatternsCondition?.patternValues ?: emptySet()).map { path ->
                        Triple(httpMethod.name, path, "${method.beanType.simpleName}#${method.method.name}")
                    }
                }
            }

    @Test
    @DisplayName("등록된 mapping 이 endpoint 12개와 정확히 일치한다 - 누락·중복·의도치 않은 method 없음")
    fun `mapping 12개 정확히 일치`() {
        val expected =
            listOf(
                Triple("POST", "/api/auth/signup", "AuthController#signup"),
                Triple("POST", "/api/auth/login", "AuthController#login"),
                Triple("POST", "/api/auth/reissue", "AuthController#reissue"),
                Triple("POST", "/api/auth/logout", "AuthController#logout"),
                Triple("POST", "/api/auth/oauth/kakao/authorization", "AuthController#kakaoAuthorization"),
                Triple("POST", "/api/auth/oauth/google/authorization", "AuthController#googleAuthorization"),
                Triple("POST", "/api/auth/oauth/kakao/login", "AuthController#kakaoLogin"),
                Triple("POST", "/api/auth/oauth/google/login", "AuthController#googleLogin"),
                Triple("POST", "/api/auth/email-verifications", "EmailVerificationController#requestVerification"),
                Triple("POST", "/api/auth/email-verifications/confirm", "EmailVerificationController#confirmVerification"),
                Triple("POST", "/api/auth/password-resets", "PasswordResetController#requestReset"),
                Triple("POST", "/api/auth/password-resets/confirm", "PasswordResetController#confirmReset"),
            )

        assertThat(authMappings()).containsExactlyInAnyOrderElementsOf(expected)
    }

    @Test
    @DisplayName("GET·PUT·DELETE 등 의도하지 않은 HTTP method mapping 이 없다")
    fun `POST 이외 mapping 없음`() {
        assertThat(authMappings().map { it.first }).containsOnly("POST")
    }

    @Test
    @DisplayName("controller 3개가 bean 으로 각각 1개씩 등록된다")
    fun `controller bean 등록`() {
        assertThat(context.getBeanNamesForType(AuthController::class.java)).hasSize(1)
        assertThat(context.getBeanNamesForType(EmailVerificationController::class.java)).hasSize(1)
        assertThat(context.getBeanNamesForType(PasswordResetController::class.java)).hasSize(1)
    }
}
