package com.dongnemarket.auth.dto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * auth DTO 의 **OpenAPI schema 계약**을 고정한다.
 *
 * 이 테스트가 왜 따로 있나 — auth DTO 를 Kotlin 으로 옮기면서 계약이 세 갈래로 갈라졌다.
 *
 * | 계약 | 무엇을 정하나 | 검증하는 곳 |
 * |---|---|---|
 * | JVM getter 이름 | Java 호출부가 부르는 메서드 이름 | `AuthKotlinInteropCompatibilityTest` |
 * | Jackson JSON 필드명 | 실제 요청·응답 본문의 키 | `AuthKotlinInteropCompatibilityTest` |
 * | OpenAPI schema | 프론트가 보고 개발하는 API 문서 | **이 테스트** |
 *
 * 셋은 서로 자동으로 따라오지 않는다. 실제로 `@get:JvmName` 만 붙인 요청 DTO 들은
 * **JVM·JSON 계약은 멀쩡한데 OpenAPI 문서만 깨졌다** — springdoc 이 `isAutoLogin()` getter 를
 * 별도 프로퍼티로 읽어 `isAutoLogin` 팬텀 필드를 만들고 required 로 올렸으며, 정상 필드
 * `autoLogin` 은 writeOnly 로 뒤집혔다. 기존 테스트는 전부 통과했고, `/v3/api-docs` 를 직접
 * 받아 전환 전(Java) 결과와 비교해서야 발견됐다.
 *
 * 그래서 기대값은 **전환 전 `origin/develop`(Java) 의 `/v3/api-docs` 실측 결과**다.
 * 전체 JSON 을 snapshot 으로 굳히면 무관한 정렬·추가로 깨지므로, auth DTO 계약에 필요한
 * 구조(프로퍼티 이름 집합 · required · readOnly/writeOnly)만 선별해 고정한다.
 * develop 서버가 따로 떠 있을 필요는 없다 — 기준값은 아래 상수에 박아둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthOpenApiContractTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private lateinit var schemas: JsonNode

    @BeforeEach
    fun fetchApiDocs() {
        val result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk).andReturn()
        result.response.characterEncoding = Charsets.UTF_8.name()
        val document = objectMapper.readTree(result.response.contentAsString)
        schemas = document.path("components").path("schemas")
    }

    private fun propertyNames(schema: String): Set<String> {
        val properties = schemas.path(schema).path("properties")
        return properties.fieldNames().asSequence().toSet()
    }

    private fun requiredNames(schema: String): Set<String> {
        val required = schemas.path(schema).path("required")
        return required.map { it.asText() }.toSet()
    }

    /** readOnly / writeOnly 가 켜진 프로퍼티 이름. 전환 전(Java) 기준으로는 auth DTO 에 하나도 없다. */
    private fun readWriteFlagged(schema: String): Set<String> {
        val properties = schemas.path(schema).path("properties")
        val flagged = mutableSetOf<String>()
        for ((name, node) in properties.fields()) {
            val readOnly = node.path("readOnly").asBoolean(false)
            val writeOnly = node.path("writeOnly").asBoolean(false)
            if (readOnly || writeOnly) {
                flagged.add(name)
            }
        }
        return flagged
    }

    @Test
    fun `auth schema 들이 OpenAPI 문서에 실제로 존재한다`() {
        assertThat(schemas.isMissingNode).isFalse()
        val documented = schemas.fieldNames().asSequence().toSet()
        assertThat(documented).containsAll(DEVELOP_BASELINE.keys)
    }

    @Nested
    @DisplayName("LoginRequest — @get:JvmName(\"isAutoLogin\") 이 붙은 요청 DTO")
    inner class LoginRequestContract {
        @Test
        fun `프로퍼티는 email, password, autoLogin 셋뿐이다`() {
            val actual = propertyNames("LoginRequest")
            assertThat(actual).containsExactlyInAnyOrder("email", "password", "autoLogin")
        }

        @Test
        fun `isAutoLogin 팬텀 프로퍼티가 없다`() {
            assertThat(propertyNames("LoginRequest")).doesNotContain("isAutoLogin")
        }

        @Test
        fun `전환 전에 없던 required 가 생기지 않는다`() {
            assertThat(requiredNames("LoginRequest")).isEmpty()
        }

        @Test
        fun `autoLogin 이 writeOnly 로 뒤집히지 않는다`() {
            assertThat(readWriteFlagged("LoginRequest")).isEmpty()
        }
    }

    @Nested
    @DisplayName("SignupRequest — boolean 동의 항목 2개에 @get:JvmName 이 붙어 있다")
    inner class SignupRequestContract {
        @Test
        fun `프로퍼티 이름 집합이 전환 전 Java schema 와 같다`() {
            val expected = DEVELOP_BASELINE.getValue("SignupRequest")
            assertThat(propertyNames("SignupRequest")).containsExactlyInAnyOrderElementsOf(expected)
        }

        @Test
        fun `isTermsAgreed, isPersonalInfoCollectionAgreed 팬텀 프로퍼티가 없다`() {
            val actual = propertyNames("SignupRequest")
            assertThat(actual).doesNotContain("isTermsAgreed", "isPersonalInfoCollectionAgreed")
        }

        @Test
        fun `전환 전에 없던 required 가 생기지 않는다`() {
            assertThat(requiredNames("SignupRequest")).isEmpty()
        }

        @Test
        fun `readOnly 나 writeOnly 로 뒤집힌 프로퍼티가 없다`() {
            assertThat(readWriteFlagged("SignupRequest")).isEmpty()
        }
    }

    @Nested
    @DisplayName("EmailVerificationConfirmResponse — 응답 DTO")
    inner class EmailVerificationConfirmResponseContract {
        @Test
        fun `프로퍼티는 email, verified 둘뿐이다`() {
            val actual = propertyNames("EmailVerificationConfirmResponse")
            assertThat(actual).containsExactlyInAnyOrder("email", "verified")
        }

        @Test
        fun `isVerified 팬텀 프로퍼티가 없다`() {
            val actual = propertyNames("EmailVerificationConfirmResponse")
            assertThat(actual).doesNotContain("isVerified")
        }

        @Test
        fun `전환 전에 없던 required 가 생기지 않는다`() {
            assertThat(requiredNames("EmailVerificationConfirmResponse")).isEmpty()
        }
    }

    @Nested
    @DisplayName("auth DTO 전수 — 전환 전 Java schema 와 대조")
    inner class AllAuthSchemas {
        @Test
        fun `프로퍼티 이름 집합이 전부 전환 전과 같다`() {
            val mismatched = mutableListOf<String>()
            for ((schema, expected) in DEVELOP_BASELINE) {
                val actual = propertyNames(schema)
                if (actual != expected) {
                    mismatched.add("$schema: expected=$expected actual=$actual")
                }
            }
            assertThat(mismatched).isEmpty()
        }

        /**
         * 전환 전 Java 결과에는 auth DTO 어디에도 required 가 없었다. Kotlin 은 non-null 타입을
         * springdoc 이 자동으로 required 로 올리기 때문에, 명시하지 않으면 문서 계약이 조용히 바뀐다.
         */
        @Test
        fun `required 가 새로 생긴 schema 가 없다`() {
            val withRequired = mutableListOf<String>()
            for (schema in DEVELOP_BASELINE.keys) {
                val required = requiredNames(schema)
                if (required.isNotEmpty()) {
                    withRequired.add("$schema: $required")
                }
            }
            assertThat(withRequired).isEmpty()
        }

        @Test
        fun `readOnly 나 writeOnly 가 새로 생긴 프로퍼티가 없다`() {
            val flagged = mutableListOf<String>()
            for (schema in DEVELOP_BASELINE.keys) {
                val names = readWriteFlagged(schema)
                if (names.isNotEmpty()) {
                    flagged.add("$schema: $names")
                }
            }
            assertThat(flagged).isEmpty()
        }

        /**
         * `is` 로 시작하는 프로퍼티는 전환 전 auth schema 에 하나도 없었다. 실제 JSON 계약이 `is` 로
         * 시작하는 필드가 새로 필요해진다면 기준값(DEVELOP_BASELINE)을 함께 고쳐야 한다 —
         * 무조건 금지가 아니라 "전환 전과 달라졌는지"가 판단 기준이다.
         */
        @Test
        fun `전환 전에 없던 is 접두 프로퍼티가 생기지 않는다`() {
            val phantom = mutableListOf<String>()
            for ((schema, expected) in DEVELOP_BASELINE) {
                val suspects = propertyNames(schema).filter { it.startsWith("is") && it !in expected }
                if (suspects.isNotEmpty()) {
                    phantom.add("$schema: $suspects")
                }
            }
            assertThat(phantom).isEmpty()
        }
    }

    companion object {
        /**
         * 기준값 — 전환 전 `origin/develop`(`cebb0a5`, 전부 Java) 을 실제로 기동해 받은
         * `/v3/api-docs` 의 auth schema 프로퍼티 이름 집합. required·readOnly·writeOnly 는
         * 그 시점에 **전 schema 통틀어 0건**이라 별도 표로 두지 않고 위에서 "비어 있음"으로 검증한다.
         */
        private val DEVELOP_BASELINE: Map<String, Set<String>> =
            mapOf(
                "LoginRequest" to setOf("email", "password", "autoLogin"),
                "SignupRequest" to
                    setOf("email", "password", "nickname", "termsAgreed", "personalInfoCollectionAgreed"),
                "SignupResponse" to setOf("memberId", "email", "nickname"),
                "EmailVerificationRequest" to setOf("email"),
                "EmailVerificationConfirmRequest" to setOf("email", "code"),
                "EmailVerificationConfirmResponse" to setOf("email", "verified"),
                "PasswordResetRequest" to setOf("email"),
                "PasswordResetConfirmRequest" to setOf("token", "newPassword"),
                "OAuthLoginRequest" to setOf("code", "state"),
                "OAuthAuthorizationStart" to setOf("authorizationUrl", "state", "expiresInSeconds"),
                "AccessTokenResponse" to setOf("accessToken"),
            )
    }
}
