package com.dongnemarket.auth.dto;

import com.dongnemarket.auth.client.OAuthUserIdentity;
import com.dongnemarket.auth.entity.OAuthProvider;
import com.dongnemarket.auth.repository.OAuthAuthorizationState;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * auth 기반 타입을 Java → Kotlin 으로 전환한 뒤에도 **Java 쪽에서 보이는 계약이 그대로인지** 검증한다.
 * <p>이 테스트는 의도적으로 <b>Java 로 작성</b>했다 — 검증 대상이 "Java 호출부가 계속 컴파일·동작하는가"이므로,
 * Kotlin 으로 쓰면 정작 확인하려던 것(JVM 시그니처·record 접근자·boolean getter 이름)을 확인하지 못한다.
 * <p>기능 검증이 아니라 <b>전환 등가성</b> 검증이다. 기존 625개 테스트가 동작을 검증하고,
 * 이 테스트는 그 테스트들이 건드리지 않는 언어 경계(JVM 표면·JSON 필드명)를 검증한다.
 */
@JsonTest
@ActiveProfiles("test")
class AuthKotlinInteropCompatibilityTest {

	@Autowired
	private ObjectMapper objectMapper;

	private static ValidatorFactory validatorFactory;
	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void tearDownValidator() {
		if (validatorFactory != null) {
			validatorFactory.close();
		}
	}

	@Nested
	@DisplayName("@JvmRecord 로 전환한 타입")
	class JvmRecords {

		@Test
		@DisplayName("JVM 상에서 여전히 진짜 record 다 (java.lang.Record 상속)")
		void stillRealRecords() {
			assertThat(OAuthUserIdentity.class.isRecord()).isTrue();
			assertThat(OAuthAuthorizationStart.class.isRecord()).isTrue();
			assertThat(OAuthAuthorizationState.class.isRecord()).isTrue();
		}

		@Test
		@DisplayName("Java 에서 record 접근자(getXxx 아님)를 그대로 호출할 수 있다")
		void recordAccessorsUnchanged() {
			// 이 호출들이 컴파일된다는 사실 자체가 접근자 이름이 유지됐다는 증거다.
			OAuthUserIdentity identity = new OAuthUserIdentity(OAuthProvider.KAKAO, "provider-user-1", "user@kakao.com");
			assertThat(identity.provider()).isEqualTo(OAuthProvider.KAKAO);
			assertThat(identity.providerUserId()).isEqualTo("provider-user-1");
			assertThat(identity.email()).isEqualTo("user@kakao.com");

			OAuthAuthorizationStart start = new OAuthAuthorizationStart("https://kauth.kakao.com/oauth/authorize", "state-1", 300L);
			assertThat(start.authorizationUrl()).isEqualTo("https://kauth.kakao.com/oauth/authorize");
			assertThat(start.state()).isEqualTo("state-1");
			assertThat(start.expiresInSeconds()).isEqualTo(300L);

			Instant issuedAt = Instant.ofEpochMilli(1_700_000_000_000L);
			OAuthAuthorizationState state = new OAuthAuthorizationState(
					OAuthProvider.GOOGLE, "bcid-hash", "https://app/callback", "verifier", "nonce", issuedAt);
			assertThat(state.provider()).isEqualTo(OAuthProvider.GOOGLE);
			assertThat(state.browserCorrelationHash()).isEqualTo("bcid-hash");
			assertThat(state.redirectUri()).isEqualTo("https://app/callback");
			assertThat(state.codeVerifier()).isEqualTo("verifier");
			assertThat(state.oidcNonce()).isEqualTo("nonce");
			assertThat(state.issuedAt()).isEqualTo(issuedAt);
		}

		@Test
		@DisplayName("oidcNonce 는 null 을 허용한다 — 카카오 흐름이 실제로 null 을 넣는다")
		void oidcNonceAcceptsNull() {
			OAuthAuthorizationState kakaoState = new OAuthAuthorizationState(
					OAuthProvider.KAKAO, "bcid-hash", "https://app/callback", "verifier", null, Instant.now());
			assertThat(kakaoState.oidcNonce()).isNull();
		}

		@Test
		@DisplayName("record 는 원래도 값 기반 equals/hashCode 를 가졌고, 그 동작이 유지된다")
		void recordValueEqualityPreserved() {
			OAuthUserIdentity a = new OAuthUserIdentity(OAuthProvider.KAKAO, "id-1", "a@b.com");
			OAuthUserIdentity b = new OAuthUserIdentity(OAuthProvider.KAKAO, "id-1", "a@b.com");
			assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
		}
	}

	@Nested
	@DisplayName("일반 DTO 로 전환한 타입")
	class PlainDtos {

		@Test
		@DisplayName("기존에 없던 값 기반 equals/hashCode 가 생기지 않았다 (data class 로 바꾸지 않음)")
		void noValueEqualitySnuckIn() {
			assertThat(new LoginRequest("a@b.com", "pw"))
					.isNotEqualTo(new LoginRequest("a@b.com", "pw"));
			assertThat(new SignupRequest("a@b.com", "pw", "nick", true, true))
					.isNotEqualTo(new SignupRequest("a@b.com", "pw", "nick", true, true));
			assertThat(AccessTokenResponse.of("token"))
					.isNotEqualTo(AccessTokenResponse.of("token"));
			assertThat(LoginResponse.of("a", "r")).isNotEqualTo(LoginResponse.of("a", "r"));
			assertThat(TokenResponse.of("a", "r")).isNotEqualTo(TokenResponse.of("a", "r"));
		}

		@Test
		@DisplayName("equals/hashCode 를 직접 선언하지 않는다 — Object 의 것을 그대로 쓴다")
		void equalsAndHashCodeStillInheritedFromObject() throws Exception {
			Class<?>[] plainDtos = {
					LoginRequest.class, SignupRequest.class, OAuthLoginRequest.class,
					EmailVerificationRequest.class, EmailVerificationConfirmRequest.class,
					EmailVerificationResponse.class, EmailVerificationConfirmResponse.class,
					PasswordResetRequest.class, PasswordResetConfirmRequest.class,
					AccessTokenResponse.class, LoginResponse.class, TokenResponse.class, SignupResponse.class,
			};
			for (Class<?> type : plainDtos) {
				assertThat(type.getMethod("equals", Object.class).getDeclaringClass())
						.as("%s.equals 선언 클래스", type.getSimpleName()).isEqualTo(Object.class);
				assertThat(type.getMethod("hashCode").getDeclaringClass())
						.as("%s.hashCode 선언 클래스", type.getSimpleName()).isEqualTo(Object.class);
				assertThat(type.isRecord()).as("%s 는 record 가 아니어야 한다", type.getSimpleName()).isFalse();
			}
		}

		@Test
		@DisplayName("기존 Java 생성자·정적 팩토리가 그대로 호출된다")
		void constructorsAndFactoriesUnchanged() {
			// LoginRequest 의 2인자 생성자 — 기존 Java 테스트가 13곳에서 쓴다.
			LoginRequest twoArg = new LoginRequest("a@b.com", "password123");
			assertThat(twoArg.getEmail()).isEqualTo("a@b.com");
			assertThat(twoArg.getPassword()).isEqualTo("password123");
			assertThat(twoArg.isAutoLogin()).isFalse();

			assertThat(new LoginRequest("a@b.com", "pw", true).isAutoLogin()).isTrue();
			assertThat(new EmailVerificationRequest("a@b.com").getEmail()).isEqualTo("a@b.com");
			assertThat(new EmailVerificationConfirmRequest("a@b.com", "123456").getCode()).isEqualTo("123456");
			assertThat(new PasswordResetRequest("a@b.com").getEmail()).isEqualTo("a@b.com");
			assertThat(new PasswordResetConfirmRequest("tok", "newPassword1!").getToken()).isEqualTo("tok");
			assertThat(AccessTokenResponse.of("at").getAccessToken()).isEqualTo("at");
			assertThat(LoginResponse.of("at", "rt").getRefreshToken()).isEqualTo("rt");
			assertThat(TokenResponse.of("at", "rt").getAccessToken()).isEqualTo("at");
		}

		@Test
		@DisplayName("boolean getter 이름이 유지된다 (isXxx, getXxx 아님)")
		void booleanGetterNamesUnchanged() throws Exception {
			// 컴파일되는 것 자체가 1차 증거이고, 리플렉션으로 getXxx 가 생기지 않았음까지 확인한다.
			assertThat(new SignupRequest("a@b.com", "pw", "nick", true, false).isTermsAgreed()).isTrue();
			assertThat(new SignupRequest("a@b.com", "pw", "nick", true, false).isPersonalInfoCollectionAgreed()).isFalse();
			assertThat(new EmailVerificationConfirmResponse("a@b.com", true).isVerified()).isTrue();

			assertThat(LoginRequest.class.getMethod("isAutoLogin")).isNotNull();
			assertThat(SignupRequest.class.getMethod("isTermsAgreed")).isNotNull();
			assertThat(SignupRequest.class.getMethod("isPersonalInfoCollectionAgreed")).isNotNull();
			assertThat(EmailVerificationConfirmResponse.class.getMethod("isVerified")).isNotNull();

			assertThat(methodNames(LoginRequest.class))
					.doesNotContain("getAutoLogin");
			assertThat(methodNames(SignupRequest.class))
					.doesNotContain("getTermsAgreed", "getPersonalInfoCollectionAgreed");
			assertThat(methodNames(EmailVerificationConfirmResponse.class))
					.doesNotContain("getVerified");
		}

		@Test
		@DisplayName("nullable 필드는 null 을 그대로 받는다 — non-null 로 조이지 않았다")
		void nullableFieldsStillAcceptNull() {
			LoginRequest request = new LoginRequest(null, null);
			assertThat(request.getEmail()).isNull();
			assertThat(request.getPassword()).isNull();
			assertThat(SignupResponse.class).isNotNull();
			assertThat(AccessTokenResponse.of(null).getAccessToken()).isNull();
		}
	}

	@Nested
	@DisplayName("Jackson JSON 계약")
	class JsonContract {

		@Test
		@DisplayName("요청 DTO 역직렬화 — 필드명이 그대로다")
		void requestDeserialization() throws Exception {
			LoginRequest login = objectMapper.readValue(
					"{\"email\":\"a@b.com\",\"password\":\"pw\",\"autoLogin\":true}", LoginRequest.class);
			assertThat(login.getEmail()).isEqualTo("a@b.com");
			assertThat(login.isAutoLogin()).isTrue();

			SignupRequest signup = objectMapper.readValue(
					"{\"email\":\"a@b.com\",\"password\":\"pw\",\"nickname\":\"nick\","
							+ "\"termsAgreed\":true,\"personalInfoCollectionAgreed\":true}", SignupRequest.class);
			assertThat(signup.getNickname()).isEqualTo("nick");
			assertThat(signup.isTermsAgreed()).isTrue();
			assertThat(signup.isPersonalInfoCollectionAgreed()).isTrue();

			OAuthLoginRequest oauth = objectMapper.readValue(
					"{\"code\":\"c\",\"state\":\"s\"}", OAuthLoginRequest.class);
			assertThat(oauth.getCode()).isEqualTo("c");
			assertThat(oauth.getState()).isEqualTo("s");
		}

		@Test
		@DisplayName("boolean 필드가 빠지면 false 로 채워진다 (Java 필드 기본값과 동일)")
		void booleanDefaultsWhenAbsent() throws Exception {
			LoginRequest login = objectMapper.readValue("{\"email\":\"a@b.com\",\"password\":\"pw\"}", LoginRequest.class);
			assertThat(login.isAutoLogin()).isFalse();

			SignupRequest signup = objectMapper.readValue(
					"{\"email\":\"a@b.com\",\"password\":\"pw\",\"nickname\":\"nick\"}", SignupRequest.class);
			assertThat(signup.isTermsAgreed()).isFalse();
			assertThat(signup.isPersonalInfoCollectionAgreed()).isFalse();
		}

		@Test
		@DisplayName("문자열 필드가 빠지면 null 이다 (예외로 바뀌지 않았다)")
		void absentStringFieldsBecomeNull() throws Exception {
			LoginRequest login = objectMapper.readValue("{}", LoginRequest.class);
			assertThat(login.getEmail()).isNull();
			assertThat(login.getPassword()).isNull();
		}

		@Test
		@DisplayName("응답 DTO 직렬화 — 필드명이 정확히 유지된다 (isXxx 같은 새 필드가 생기지 않는다)")
		void responseSerializationFieldNames() throws Exception {
			assertThat(objectMapper.writeValueAsString(AccessTokenResponse.of("at")))
					.isEqualTo("{\"accessToken\":\"at\"}");
			assertThat(objectMapper.writeValueAsString(TokenResponse.of("at", "rt")))
					.isEqualTo("{\"accessToken\":\"at\",\"refreshToken\":\"rt\"}");

			String confirm = objectMapper.writeValueAsString(new EmailVerificationConfirmResponse("a@b.com", true));
			assertThat(confirm).contains("\"verified\":true").contains("\"email\":\"a@b.com\"");
			assertThat(confirm).doesNotContain("isVerified");

			String start = objectMapper.writeValueAsString(
					new OAuthAuthorizationStart("https://auth", "state-1", 300L));
			assertThat(start).contains("\"authorizationUrl\":\"https://auth\"")
					.contains("\"state\":\"state-1\"")
					.contains("\"expiresInSeconds\":300");
		}
	}

	@Nested
	@DisplayName("Bean Validation")
	class BeanValidation {

		@Test
		@DisplayName("@field: use-site target 이 적용돼 제약이 살아 있다 — 빈 값이면 위반이 검출된다")
		void constraintsStillApply() {
			Set<ConstraintViolation<LoginRequest>> violations = validator.validate(new LoginRequest("", ""));
			assertThat(violations).hasSize(2);
			assertThat(violatedProperties(violations)).containsExactlyInAnyOrder("email", "password");
		}

		@Test
		@DisplayName("이메일 형식 위반이 검출된다")
		void emailFormatViolation() {
			Set<ConstraintViolation<LoginRequest>> violations = validator.validate(new LoginRequest("not-an-email", "pw"));
			assertThat(violatedProperties(violations)).containsExactly("email");
			assertThat(violations.iterator().next().getMessage()).isEqualTo("이메일 형식이 올바르지 않습니다.");
		}

		@Test
		@DisplayName("SignupRequest 의 @Size·@Pattern 이 유지된다")
		void signupPasswordPolicyPreserved() {
			Set<ConstraintViolation<SignupRequest>> violations =
					validator.validate(new SignupRequest("a@b.com", "short", "nick", true, true));
			assertThat(violatedProperties(violations)).containsExactly("password");
			assertThat(violations).hasSize(2); // @Size + @Pattern
		}

		@Test
		@DisplayName("유효한 값이면 위반이 없다")
		void validRequestHasNoViolations() throws Exception {
			assertThat(validator.validate(new SignupRequest("a@b.com", "Password1!ok", "nick", true, true))).isEmpty();
			assertThat(validator.validate(new PasswordResetConfirmRequest("tok", "Password1!ok"))).isEmpty();
			assertThat(validator.validate(oauthLoginRequest("{\"code\":\"c\",\"state\":\"s\"}"))).isEmpty();
		}

		@Test
		@DisplayName("OAuthLoginRequest 의 @NotBlank 두 개가 유지된다 — 빈 값·필드 누락 모두 동일하게 검출된다")
		void oauthLoginRequestConstraints() throws Exception {
			assertThat(violatedProperties(validator.validate(oauthLoginRequest("{\"code\":\"\",\"state\":\"\"}"))))
					.containsExactlyInAnyOrder("code", "state");
			// 필드가 아예 빠지면 null → @NotBlank 가 동일하게 위반을 낸다(원본 Java 동작과 같다).
			assertThat(violatedProperties(validator.validate(oauthLoginRequest("{}"))))
					.containsExactlyInAnyOrder("code", "state");
			// 명시적 null 도 마찬가지다.
			assertThat(violatedProperties(validator.validate(oauthLoginRequest("{\"code\":null,\"state\":null}"))))
					.containsExactlyInAnyOrder("code", "state");
		}
	}

	/**
	 * `OAuthLoginRequest` 는 다른 요청 DTO 와 달리 원본 Java 구조(protected 무인자 생성자 + private 필드)를
	 * 그대로 유지했다. 주 생성자 프로퍼티로 옮기면 원본에 없던 public 2인자 생성자가 공개 API 에 추가되기 때문이다.
	 * 이 클래스가 그 결정을 고정한다.
	 */
	@Nested
	@DisplayName("OAuthLoginRequest 생성자 표면")
	class OAuthLoginRequestConstructorSurface {

		@Test
		@DisplayName("public 생성자가 하나도 없다 — 원본과 동일하게 공개 표면이 넓어지지 않았다")
		void noPublicConstructorAdded() {
			assertThat(OAuthLoginRequest.class.getConstructors()).isEmpty();
		}

		@Test
		@DisplayName("protected 무인자 생성자가 유일한 생성자다")
		void onlyProtectedNoArgConstructor() throws Exception {
			java.lang.reflect.Constructor<?>[] declared = OAuthLoginRequest.class.getDeclaredConstructors();
			assertThat(declared).hasSize(1);
			assertThat(declared[0].getParameterCount()).isZero();
			assertThat(java.lang.reflect.Modifier.isProtected(declared[0].getModifiers())).isTrue();
		}

		@Test
		@DisplayName("클래스가 final 이 아니다 — 원본 Java 클래스와 같다")
		void classIsNotFinal() {
			assertThat(java.lang.reflect.Modifier.isFinal(OAuthLoginRequest.class.getModifiers())).isFalse();
		}

		@Test
		@DisplayName("Jackson 역직렬화는 기존과 동일하게 동작한다 (무인자 생성자 + 필드 주입)")
		void jacksonStillDeserializes() throws Exception {
			OAuthLoginRequest request = oauthLoginRequest("{\"code\":\"auth-code\",\"state\":\"state-1\"}");
			assertThat(request.getCode()).isEqualTo("auth-code");
			assertThat(request.getState()).isEqualTo("state-1");
		}

		@Test
		@DisplayName("필드가 빠지거나 null 이면 null 이다 — 예외로 바뀌지 않았다")
		void absentOrNullFieldsBecomeNull() throws Exception {
			assertThat(oauthLoginRequest("{}").getCode()).isNull();
			assertThat(oauthLoginRequest("{}").getState()).isNull();
			assertThat(oauthLoginRequest("{\"code\":null,\"state\":null}").getCode()).isNull();
		}
	}

	private OAuthLoginRequest oauthLoginRequest(String json) throws Exception {
		return objectMapper.readValue(json, OAuthLoginRequest.class);
	}

	private static Set<String> methodNames(Class<?> type) {
		return java.util.Arrays.stream(type.getMethods()).map(java.lang.reflect.Method::getName)
				.collect(Collectors.toSet());
	}

	private static <T> Set<String> violatedProperties(Set<ConstraintViolation<T>> violations) {
		return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
	}
}
