package com.dongnemarket.auth.dto

import com.dongnemarket.auth.client.OAuthUserIdentity
import com.dongnemarket.auth.entity.OAuthProvider
import com.dongnemarket.auth.repository.OAuthAuthorizationState
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.ConstraintViolation
import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * auth DTO 의 **와이어 계약(JSON 필드명·역직렬화 동작)·Bean Validation·설계 고정**을 검증한다.
 *
 * 전신은 Java 로 작성됐던 `AuthKotlinInteropCompatibilityTest` 다. 그 테스트는 "Java 호출부가 계속
 * 컴파일·동작하는가"를 검증했는데, auth·member 전환 완료로 **저장소에 Java 호출자가 0이 되면서**
 * 순수 JVM 표면 검증(record 접근자 이름·getter finality·protected 생성자 표면 등)은 검증 대상을
 * 잃어 제거했다. 여기 남긴 것은 언어와 무관하게 유효한 계약이다 —
 * 프론트·모바일과의 JSON 계약, 검증 제약·메시지, 그리고 전환 때 정한 설계 결정
 * (data class 금지·불변성·nullability)의 고정. 항목별 삭제·대체 근거는
 * `docs/kotlin-migration/auth-migration-notes.md` 「PR H」절에 기록돼 있다.
 */
@JsonTest
@ActiveProfiles("test")
class AuthDtoContractTest {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Nested
    @DisplayName("JSON 와이어 계약 — 필드명·기본값·누락 처리")
    inner class JsonContract {
        @Test
        fun `요청 역직렬화 - 필드명이 그대로다`() {
            val login =
                objectMapper.readValue(
                    """{"email":"a@b.com","password":"pw","autoLogin":true}""",
                    LoginRequest::class.java,
                )
            assertThat(login.email).isEqualTo("a@b.com")
            assertThat(login.autoLogin).isTrue()

            val signup =
                objectMapper.readValue(
                    """{"email":"a@b.com","password":"pw","nickname":"nick","termsAgreed":true,"personalInfoCollectionAgreed":true}""",
                    SignupRequest::class.java,
                )
            assertThat(signup.nickname).isEqualTo("nick")
            assertThat(signup.termsAgreed).isTrue()
            assertThat(signup.personalInfoCollectionAgreed).isTrue()

            val oauth = objectMapper.readValue("""{"code":"c","state":"s"}""", OAuthLoginRequest::class.java)
            assertThat(oauth.getCode()).isEqualTo("c")
            assertThat(oauth.getState()).isEqualTo("s")
        }

        @Test
        fun `정상 요청 body 가 전부 채워진다`() {
            val signup =
                objectMapper.readValue(
                    """{"email":"a@b.com","password":"Password1!ok","nickname":"nick","termsAgreed":true,"personalInfoCollectionAgreed":true}""",
                    SignupRequest::class.java,
                )
            assertThat(signup.email).isEqualTo("a@b.com")
            assertThat(signup.password).isEqualTo("Password1!ok")

            assertThat(
                objectMapper
                    .readValue("""{"email":"a@b.com","code":"123456"}""", EmailVerificationConfirmRequest::class.java)
                    .code,
            ).isEqualTo("123456")
            assertThat(
                objectMapper
                    .readValue("""{"token":"tok","newPassword":"Password1!ok"}""", PasswordResetConfirmRequest::class.java)
                    .newPassword,
            ).isEqualTo("Password1!ok")
            assertThat(
                objectMapper
                    .readValue("""{"code":"auth-code","state":"state-1"}""", OAuthLoginRequest::class.java)
                    .getCode(),
            ).isEqualTo("auth-code")
        }

        @Test
        fun `boolean 필드는 빠지면 false, 명시하면 그 값이다`() {
            val absent = objectMapper.readValue("""{"email":"a@b.com","password":"pw"}""", LoginRequest::class.java)
            assertThat(absent.autoLogin).isFalse()

            val explicit =
                objectMapper.readValue(
                    """{"email":"a@b.com","password":"pw","autoLogin":false}""",
                    LoginRequest::class.java,
                )
            assertThat(explicit.autoLogin).isFalse()

            val partial =
                objectMapper.readValue(
                    """{"email":"a@b.com","password":"pw","nickname":"n","termsAgreed":true}""",
                    SignupRequest::class.java,
                )
            assertThat(partial.termsAgreed).isTrue()
            assertThat(partial.personalInfoCollectionAgreed).isFalse()
        }

        @Test
        fun `문자열 필드는 누락·명시적 null 이면 null, 빈 문자열이면 빈 문자열이다`() {
            // 누락 → null (예외로 바뀌지 않았다)
            val empty = objectMapper.readValue("{}", LoginRequest::class.java)
            assertThat(empty.email).isNull()
            assertThat(empty.password).isNull()
            assertThat(objectMapper.readValue("{}", EmailVerificationRequest::class.java).email).isNull()
            assertThat(objectMapper.readValue("{}", PasswordResetConfirmRequest::class.java).token).isNull()
            assertThat(objectMapper.readValue("{}", OAuthLoginRequest::class.java).getCode()).isNull()

            // 명시적 null → null
            assertThat(objectMapper.readValue("""{"email":null}""", EmailVerificationRequest::class.java).email).isNull()
            assertThat(
                objectMapper.readValue("""{"code":null,"state":null}""", OAuthLoginRequest::class.java).getState(),
            ).isNull()

            // 빈 문자열은 빈 문자열로 유지 — null 로 바뀌지 않는다. Validation 이 걸러낼 몫이다.
            assertThat(objectMapper.readValue("""{"email":""}""", EmailVerificationRequest::class.java).email).isEmpty()
        }

        @Test
        fun `응답 직렬화 - 필드명이 정확히 유지된다 (isXxx 같은 새 필드가 생기지 않는다)`() {
            assertThat(objectMapper.writeValueAsString(AccessTokenResponse.of("at")))
                .isEqualTo("""{"accessToken":"at"}""")
            assertThat(objectMapper.writeValueAsString(TokenResponse.of("at", "rt")))
                .isEqualTo("""{"accessToken":"at","refreshToken":"rt"}""")

            val confirm = objectMapper.writeValueAsString(EmailVerificationConfirmResponse("a@b.com", true))
            assertThat(confirm).contains(""""verified":true""").contains(""""email":"a@b.com"""")
            assertThat(confirm).doesNotContain("isVerified")

            val start = objectMapper.writeValueAsString(OAuthAuthorizationStart("https://auth", "state-1", 300L))
            assertThat(start)
                .contains(""""authorizationUrl":"https://auth"""")
                .contains(""""state":"state-1"""")
                .contains(""""expiresInSeconds":300""")
        }
    }

    @Nested
    @DisplayName("Bean Validation — 제약·메시지")
    inner class BeanValidationContract {
        @Test
        fun `빈 값이면 위반이 검출된다 - @field use-site target 이 살아 있다`() {
            val violations = validator.validate(LoginRequest("", ""))
            assertThat(violations).hasSize(2)
            assertThat(violatedProperties(violations)).containsExactlyInAnyOrder("email", "password")
        }

        @Test
        fun `이메일 형식 위반이 검출되고 메시지가 유지된다`() {
            val violations = validator.validate(LoginRequest("not-an-email", "pw"))
            assertThat(violatedProperties(violations)).containsExactly("email")
            assertThat(violations.first().message).isEqualTo("이메일 형식이 올바르지 않습니다.")
        }

        @Test
        fun `SignupRequest 의 비밀번호 정책(@Size·@Pattern)이 유지된다`() {
            val violations = validator.validate(SignupRequest("a@b.com", "short", "nick", true, true))
            assertThat(violatedProperties(violations)).containsExactly("password")
            assertThat(violations).hasSize(2) // @Size + @Pattern
        }

        @Test
        fun `유효한 값이면 위반이 없다`() {
            assertThat(validator.validate(SignupRequest("a@b.com", "Password1!ok", "nick", true, true))).isEmpty()
            assertThat(validator.validate(PasswordResetConfirmRequest("tok", "Password1!ok"))).isEmpty()
            assertThat(
                validator.validate(
                    objectMapper.readValue("""{"code":"c","state":"s"}""", OAuthLoginRequest::class.java),
                ),
            ).isEmpty()
        }

        @Test
        fun `OAuthLoginRequest 의 @NotBlank 두 개가 유지된다 - 빈 값·누락·명시적 null 모두 검출된다`() {
            fun violated(json: String) =
                violatedProperties(validator.validate(objectMapper.readValue(json, OAuthLoginRequest::class.java)))

            assertThat(violated("""{"code":"","state":""}""")).containsExactlyInAnyOrder("code", "state")
            assertThat(violated("{}")).containsExactlyInAnyOrder("code", "state")
            assertThat(violated("""{"code":null,"state":null}""")).containsExactlyInAnyOrder("code", "state")
        }

        @Test
        fun `역직렬화 성공과 Validation 위반은 분리돼 있다`() {
            val blank = objectMapper.readValue("""{"email":""}""", EmailVerificationRequest::class.java)
            assertThat(blank).isNotNull() // 역직렬화 자체는 성공한다
            assertThat(violatedProperties(validator.validate(blank))).containsExactly("email")

            val missing = objectMapper.readValue("{}", PasswordResetConfirmRequest::class.java)
            assertThat(missing).isNotNull()
            assertThat(violatedProperties(validator.validate(missing))).containsExactlyInAnyOrder("token", "newPassword")
        }

        @Test
        fun `제약 메시지와 대상 필드가 유지된다`() {
            val violations = validator.validate(EmailVerificationConfirmRequest("", ""))
            assertThat(violatedProperties(violations)).containsExactlyInAnyOrder("email", "code")
            assertThat(violations.map { it.message }).contains("이메일은 필수입니다.", "인증 코드는 필수입니다.")

            assertThat(validator.validate(PasswordResetRequest("")).map { it.message })
                .containsExactly("이메일은 필수입니다.")
        }
    }

    @Nested
    @DisplayName("설계 고정 — data class 금지·불변성·nullability")
    inner class DesignContract {
        /** 누군가 DTO 를 `data class` 로 바꾸면 이 테스트가 즉시 깨진다 — 값 동등성·copy·toString(토큰 노출)이 새로 생기면 안 된다. */
        @Test
        fun `DTO 13개는 equals·hashCode 를 선언하지 않고 record 도 아니다`() {
            for (type in plainDtoTypes) {
                assertThat(type.getMethod("equals", Any::class.java).declaringClass)
                    .`as`("%s.equals 선언 클래스", type.simpleName).isEqualTo(Any::class.java)
                assertThat(type.getMethod("hashCode").declaringClass)
                    .`as`("%s.hashCode 선언 클래스", type.simpleName).isEqualTo(Any::class.java)
                assertThat(type.isRecord).`as`("%s 는 record 가 아니어야 한다", type.simpleName).isFalse()
            }
        }

        @Test
        fun `값이 같은 두 인스턴스는 같지 않다 - 값 기반 동등성이 생기지 않았다`() {
            assertThat(LoginRequest("a@b.com", "pw")).isNotEqualTo(LoginRequest("a@b.com", "pw"))
            assertThat(SignupRequest("a@b.com", "pw", "nick", true, true))
                .isNotEqualTo(SignupRequest("a@b.com", "pw", "nick", true, true))
            assertThat(AccessTokenResponse.of("token")).isNotEqualTo(AccessTokenResponse.of("token"))
            assertThat(LoginResponse.of("a", "r")).isNotEqualTo(LoginResponse.of("a", "r"))
            assertThat(TokenResponse.of("a", "r")).isNotEqualTo(TokenResponse.of("a", "r"))
        }

        @Test
        fun `요청·응답 DTO 어디에도 public setter 가 없다 - 불변 설계 유지`() {
            for (type in plainDtoTypes) {
                assertThat(
                    type.methods
                        .filter { it.name.startsWith("set") && it.parameterCount == 1 }
                        .map { it.name },
                ).`as`("%s 에 public setter 가 생기면 안 된다", type.simpleName).isEmpty()
            }
        }

        /** null 인자 호출이 컴파일된다는 것 자체가 nullability 를 조이지 않았다는 앵커다 — 조이면 이 파일이 컴파일되지 않는다. */
        @Test
        fun `nullable 프로퍼티는 null 을 그대로 받는다`() {
            val request = LoginRequest(null, null)
            assertThat(request.email).isNull()
            assertThat(request.password).isNull()
            assertThat(AccessTokenResponse.of(null).accessToken).isNull()
        }

        /** 카카오 흐름이 실제로 null 을 넣는다 — non-null 로 조이면 런타임 NPE 로 카카오 로그인이 깨진다(마이그레이션 기록 PR A 절). */
        @Test
        fun `OAuthAuthorizationState 의 oidcNonce 는 null 을 허용한다`() {
            val kakaoState =
                OAuthAuthorizationState(
                    OAuthProvider.KAKAO, "bcid-hash", "https://app/callback", "verifier", null, Instant.now(),
                )
            assertThat(kakaoState.oidcNonce).isNull()
        }

        @Test
        fun `record 타입은 값 기반 동등성을 유지한다`() {
            val a = OAuthUserIdentity(OAuthProvider.KAKAO, "id-1", "a@b.com")
            val b = OAuthUserIdentity(OAuthProvider.KAKAO, "id-1", "a@b.com")
            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b)
        }
    }

    companion object {
        private lateinit var validatorFactory: ValidatorFactory
        private lateinit var validator: Validator

        /** equals/hashCode 미선언·불변 설계를 고정하는 대상 13개 (record 로 옮긴 3개 제외 전부). */
        private val plainDtoTypes =
            arrayOf(
                LoginRequest::class.java, SignupRequest::class.java, OAuthLoginRequest::class.java,
                EmailVerificationRequest::class.java, EmailVerificationConfirmRequest::class.java,
                EmailVerificationResponse::class.java, EmailVerificationConfirmResponse::class.java,
                PasswordResetRequest::class.java, PasswordResetConfirmRequest::class.java,
                AccessTokenResponse::class.java, LoginResponse::class.java,
                TokenResponse::class.java, SignupResponse::class.java,
            )

        @JvmStatic
        @BeforeAll
        fun setUpValidator() {
            validatorFactory = Validation.buildDefaultValidatorFactory()
            validator = validatorFactory.validator
        }

        @JvmStatic
        @AfterAll
        fun tearDownValidator() {
            validatorFactory.close()
        }

        private fun <T> violatedProperties(violations: Set<ConstraintViolation<T>>): Set<String> =
            violations.map { it.propertyPath.toString() }.toSet()
    }
}
