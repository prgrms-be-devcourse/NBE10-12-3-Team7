# auth 도메인 Java → Kotlin 마이그레이션 기록

> **이 문서의 위치**
> `backend/backend.md` 의 「Kotlin 마이그레이션」절에는 **팀 전체가 따라야 할 짧은 규칙**만 둔다.
> 이 문서는 그 규칙이 **왜** 그렇게 정해졌는지 — 실제로 무엇이 깨졌고, 어떤 대안을 왜 버렸고,
> 무엇으로 검증했는지 — 를 누적한다.
>
> **원칙**: 기능 변경과 언어 변환을 섞지 않는다. 이 문서에 기록된 모든 변경은 **언어 변환**이며,
> 동작·API·JSON·DB·Redis 계약은 그대로 유지한다. 변환 중 발견한 기존 버그는 고치지 않고
> 기록만 남긴 뒤 별도 PR 로 처리한다.

## 환경

| 항목 | 값 |
|---|---|
| Kotlin | 2.2.20 (Spring Boot BOM 의 `kotlin.version=1.9.25` 를 Boot Gradle 플러그인이 적용 플러그인 버전으로 재정렬) |
| 플러그인 | `kotlin.jvm` · `plugin.spring`(allopen) · `plugin.jpa`(noarg) · `plugin.allopen`(JPA 어노테이션) · ktlint 1.5.0 |
| JVM target | 21 (Java toolchain 상속) |
| Gradle / Boot | 8.14.5 / 3.5.15 |
| 회귀 안전망 | 기존 Java 테스트 625건 + integrationTest 44건. **CI 워크플로가 제거된 상태라 전부 로컬 수동 실행** |

## 검증 방법론 (모든 PR 공통)

언어 변환에서 "테스트가 통과한다"는 것만으로는 부족하다. 테스트가 건드리지 않는 **JVM 공개 표면**이
조용히 바뀔 수 있기 때문이다. 그래서 매 PR 마다 다음을 수행한다.

```bash
# 1) 변환 전 baseline 확보 (변환 커밋 전에 반드시)
./gradlew compileJava
javap -public build/classes/java/main/com/dongnemarket/auth/dto/Xxx.class > before_Xxx.txt
javap -p      ...   # 생성자 가시성까지 봐야 할 때

# 2) 변환 후 동일 대상 재추출 → diff
javap -public build/classes/kotlin/main/com/dongnemarket/auth/dto/Xxx.class > after_Xxx.txt
diff before_Xxx.txt after_Xxx.txt
```

**정규화 주의**: `@JvmStatic` 은 `public static` 이 아니라 `public static final` 을 만들고,
Kotlin 멤버는 기본 `final` 이다. 단순 문자열 diff 를 그대로 믿으면 **멀쩡한 메서드를 "사라졌다"고
오판**한다(실제로 PR A 에서 정적 팩토리 4개를 오판했다). `final` 수식어를 정규화한 뒤 비교한다.

판정 기준은 **"기존 멤버가 사라졌는가"** 이고, 추가분은 별도로 열거해 순수 additive 인지 검토한다.

---

# PR A — 기반 타입 (enum · record · DTO)

브랜치 `refactor/kotlin-auth-01-model` / 대상 18개 파일 / 기준 `origin/develop@5e49336`

---

## Java record → Kotlin `@JvmRecord data class`

- **Kotlin 변환 패키지**: `com.dongnemarket.auth.client` · `com.dongnemarket.auth.dto` · `com.dongnemarket.auth.repository`
- **변환 대상**: `OAuthUserIdentity`, `OAuthAuthorizationStart`, `OAuthAuthorizationState` (3개)
- **Java 기존 형태**

  ```java
  public record OAuthUserIdentity(OAuthProvider provider, String providerUserId, String email) {}
  ```

  본문이 없는 순수 record. compact constructor·커스텀 메서드 없음.

- **Kotlin 변환 형태**

  ```kotlin
  @JvmRecord
  data class OAuthUserIdentity(
      val provider: OAuthProvider?,
      val providerUserId: String?,
      val email: String?,
  )
  ```

- **Java 대비 Kotlin 변환 시 가지는 이점**
  - `@JvmRecord` 를 붙이면 JVM 상에서도 **진짜 record**(`extends java.lang.Record`)로 컴파일되어,
    Java 호출부의 `provider()` 같은 **record 접근자가 그대로 유지**된다.
  - Kotlin 쪽에서는 구조 분해(`val (p, id, email) = identity`)와 `copy()` 를 덤으로 얻는다.
  - 프로퍼티 nullability 가 타입에 드러나 이후 Kotlin 호출부에서 컴파일 타임에 걸린다.

- **변환 시 발생할 수 있는 문제점**
  - ⚠️ **가장 큰 함정**: 그냥 `data class` 로 바꾸면 접근자가 `provider()` → `getProvider()` 로 **이름이 바뀐다.**
    아직 Java 인 호출부가 전부 깨진다. 이 프로젝트에서는 **main 15곳 + test 12곳 = 27곳**이 대상이었다.
  - `@JvmRecord` 는 제약이 있다: 클래스가 `final` 이어야 하고, 주 생성자 파라미터가 전부 `val` 이어야 하며,
    본문에 추가 상태를 둘 수 없다. 본문이 있는 record 는 그대로 옮길 수 없다.
  - `data class` 라서 `copy()`/`componentN()` 이 **추가로 생긴다**. 주로 **Kotlin 호출 표면에 붙는 부산물**이며
    (구조 분해·복사), Java record 에는 없던 API 다. JVM 상으로는 public 메서드로 컴파일되므로
    `javap -public` 에도 나타나고 Java 에서도 호출 가능하지만, Java 쪽에서 이걸 쓸 이유는 없다.
  - `equals`/`hashCode`/`toString` 의 **구현 주체가 바뀐다**. Java record 는 `ObjectMethods` bootstrap 으로,
    Kotlin data class 는 자체 생성 코드로 만든다. 동작(컴포넌트 단위 동등성)은 같지만 구현은 다르다.

- **실제 프로젝트에서 발견한 문제**
  → 아래 「예외: `OAuthAuthorizationState.oidcNonce`」 항목 참고. record 자체가 아니라 **nullability** 에서 터졌다.

- **호환성을 유지한 방법**
  - `@JvmRecord` 사용. 그 결과 `AuthService.java`·`AuthController.java` 등 **운영 Java 코드를 한 줄도 수정하지 않았다.**
  - 호환성 테스트에서 `Class.isRecord()` 가 `true` 인지, Java 에서 `identity.provider()` 가 **컴파일되는지**를 검증한다
    (Java 테스트에서 그 호출이 컴파일된다는 사실 자체가 접근자 이름 유지의 증거다).

- **검증 방법**

  ```
  $ javap -public .../OAuthUserIdentity.class
  public final class com.dongnemarket.auth.client.OAuthUserIdentity extends java.lang.Record {
    public com.dongnemarket.auth.client.OAuthUserIdentity(OAuthProvider, String, String);
    public final OAuthProvider provider();      ← 유지
    public final String providerUserId();       ← 유지
    public final String email();                ← 유지
    ... (+ componentN, copy, copy$default 추가)
  }
  ```
  `extends java.lang.Record` 가 남아 있는 것이 "진짜 record"의 증거다.
  테스트: `AuthKotlinInteropCompatibilityTest.JvmRecords` 4건.

- **선택하지 않은 대안과 이유**

  | 대안 | 버린 이유 |
  |---|---|
  | 일반 `data class` + 호출부 27곳 일괄 수정 | 순수 언어 전환 PR 이 **27개 파일을 건드리는 PR** 로 번진다. `AuthService`·`AuthController` 등 이후 단계(PR D·E) 대상 파일을 미리 손대게 되어 PR 경계가 무너진다. 수정 자체도 회귀 위험이다. |
  | Java record 를 그대로 두고 나중에 전환 | `OAuthProvider`(enum)를 이 PR 에서 옮기는데 record 3개가 전부 그것을 참조한다. 남겨두면 Java→Kotlin 참조가 뒤엉켜 오히려 복잡해진다. |
  | `data class` + `@JvmName` 으로 접근자 이름 맞추기 | `@JvmName` 은 프로퍼티 getter 이름만 바꿀 뿐 record 시맨틱(`isRecord()`, `Record` 상속)을 주지 못한다. 반쪽짜리다. |

- **최종 결정**
  **본문 없는 순수 Java `record` 는 `@JvmRecord data class` 로 옮긴다** (이번에 확인한 3개가 모두 이 형태였다).
  compact constructor·커스텀 메서드·추가 상태가 있는 record 는 `@JvmRecord` 제약에 걸릴 수 있어 **별도 판단**한다.
  호출부를 기계적으로 고치지 않아도 되고,
  JVM 표면이 거의 그대로 유지된다. `copy()`/`componentN()` 추가는 순수 additive 로 수용한다.

---

## 일반 Java DTO → Kotlin 일반 `class` (data class 아님)

- **Kotlin 변환 패키지**: `com.dongnemarket.auth.dto`
- **변환 대상**: 13개
  - 요청 6: `LoginRequest`, `SignupRequest`, `EmailVerificationRequest`, `EmailVerificationConfirmRequest`, `PasswordResetRequest`, `PasswordResetConfirmRequest`
  - 응답 6: `AccessTokenResponse`, `LoginResponse`, `TokenResponse`, `SignupResponse`, `EmailVerificationResponse`, `EmailVerificationConfirmResponse`
  - (`OAuthLoginRequest` 는 구조가 달라 별도 항목)

- **Java 기존 형태**: private 필드 + 생성자 + getter 만 있는 평범한 Bean.
  **13개 전부 `equals`/`hashCode`/`toString` 을 정의하지 않았다** — `javap -public` 으로 확인한 사실이다.

- **Kotlin 변환 형태**

  ```kotlin
  class EmailVerificationResponse(
      val email: String?,
      val expiresAt: LocalDateTime?,
  )
  ```

- **Java 대비 Kotlin 변환 시 가지는 이점**
  - 필드·생성자·getter 보일러플레이트가 사라진다(`EmailVerificationResponse` 22줄 → 9줄).
  - nullability 가 타입에 드러난다.

- **변환 시 발생할 수 있는 문제점**
  - ⚠️ **`data class` 로 바꾸면 원본에 없던 동작이 생긴다.**
    - **값 기반 `equals`/`hashCode`** — 원본은 참조 동등성이었다. `Set`/`Map` 키로 쓰이거나
      `assertThat(a).isEqualTo(b)` 같은 검증이 있으면 **결과가 조용히 뒤집힌다.**
    - `copy()` — 불변으로 설계한 응답 DTO 에 변형 경로가 열린다.
    - `componentN()` — 구조 분해가 가능해지고, 이후 프로퍼티 **순서를 바꾸면 호출부가 조용히 깨진다.**
    - `toString()` — 원본은 `Object.toString()`. `TokenResponse`·`LoginResponse` 는 **토큰 값을 들고 있어서**
      `toString()` 이 값을 그대로 노출하면 로그에 토큰이 찍힐 위험이 생긴다.
  - Kotlin class 는 기본 `final` 이다. 원본 Java 클래스는 `final` 이 아니었다.

- **실제 프로젝트에서 발견한 문제**
  변환 전 `javap -public` 을 뜨지 않았다면 "DTO 니까 당연히 data class" 로 갔을 것이다.
  baseline 을 뜬 덕에 **13개 전부 equals/hashCode 가 없다**는 사실을 먼저 확인했고, 그래서 일반 class 를 선택했다.
  특히 `TokenResponse`/`LoginResponse` 의 `toString()` 토큰 노출은 테스트로는 절대 안 잡히는 종류의 회귀다.

- **호환성을 유지한 방법**
  - 전부 일반 `class`.
  - 회귀 방지 테스트를 리플렉션으로 고정했다 — `equals`/`hashCode` 의 **선언 클래스가 `Object` 인지**,
    그리고 `isRecord()` 가 `false` 인지 13개 전부 확인한다.

    ```java
    assertThat(type.getMethod("equals", Object.class).getDeclaringClass()).isEqualTo(Object.class);
    ```
    누군가 나중에 `data class` 로 바꾸면 이 테스트가 즉시 깨진다.
  - 값이 같은 두 인스턴스가 **같지 않은지**도 직접 확인한다(`isNotEqualTo`).

- **검증 방법**: `AuthKotlinInteropCompatibilityTest.PlainDtos` 5건.

- **선택하지 않은 대안과 이유**

  | 대안 | 버린 이유 |
  |---|---|
  | 전부 `data class` | 위 4가지 동작이 새로 생긴다. **순수 언어 전환 PR 에서 동작이 늘면 안 된다.** |
  | 요청은 `data class`, 응답은 `class` 로 혼용 | 기준이 "요청/응답"이 아니라 "원본에 equals 가 있었나"여야 한다. 혼용 기준은 다음 사람이 오판한다. |
  | `class` + equals/hashCode 수동 구현 | 원본에 없던 것을 새로 만드는 것이므로 더 나쁘다. |

- **최종 결정**
  **원본에 `equals`/`hashCode` 가 없던 DTO 는 `data class` 로 바꾸지 않는다.**
  판단 기준은 취향이 아니라 **변환 전 `javap -public` 결과**다. `data class` 는 원본이 `record` 였을 때만 쓴다.

---

## Boolean `isXxx()` getter 와 Jackson JSON 필드명

- **Kotlin 변환 패키지**: `com.dongnemarket.auth.dto`
- **변환 대상**: `LoginRequest.autoLogin`, `SignupRequest.termsAgreed` / `personalInfoCollectionAgreed`,
  `EmailVerificationConfirmResponse.verified` (4개 프로퍼티)

- **Java 기존 형태**

  ```java
  private boolean autoLogin;
  public boolean isAutoLogin() { return autoLogin; }   // JSON 필드명은 "autoLogin"
  ```

- **Kotlin 변환 형태**

  ```kotlin
  @get:JvmName("isAutoLogin")
  val autoLogin: Boolean = false
  ```

- **Java 대비 Kotlin 변환 시 가지는 이점**
  - 필드/getter 쌍이 사라진다. 기본값을 선언부에 둘 수 있다.

- **변환 시 발생할 수 있는 문제점**
  Kotlin 의 boolean 프로퍼티 이름 짓기는 **두 계약을 동시에 건드린다.** 이게 핵심 함정이다.

  | Kotlin 선언 | JVM getter | Jackson JSON 필드 | 판정 |
  |---|---|---|---|
  | `val autoLogin: Boolean` | `getAutoLogin()` | `autoLogin` | ❌ Java 호출부 `isAutoLogin()` 이 깨짐 |
  | `val isAutoLogin: Boolean` | `isAutoLogin()` | **`isAutoLogin`** | ❌ **JSON 계약이 깨짐** (프론트가 보내는 `autoLogin` 을 못 읽음) |
  | `@get:JvmName("isAutoLogin") val autoLogin` | `isAutoLogin()` | 상황에 따라 다름 ⚠️ | 아래 참고 |

  즉 **"프로퍼티 이름을 `isXxx` 로 바꾸면 된다"는 흔한 우회는 JSON 을 깨뜨린다.**

- **실제 프로젝트에서 발견한 문제 — `EmailVerificationConfirmResponse.verified` JSON 필드 소실** 🔴

  `@get:JvmName("isVerified")` 만 붙였더니 **응답 JSON 에서 `verified` 필드가 사라졌다.**

  ```
  EmailVerificationControllerTest > 올바른 코드로 확인하면 200과 verified=true를 반환한다 FAILED
    java.lang.AssertionError: No value at JSON path "$.data.verified"
      at EmailVerificationControllerTest.confirmVerification_correctCode_success(:136)
    Caused by: com.jayway.jsonpath.PathNotFoundException
  ```

  원인: `@get:JvmName` 으로 getter 이름을 바꾸면 jackson-module-kotlin 이 **Kotlin 프로퍼티 메타데이터**를
  기준으로 이름을 정하면서 표준 Java Bean 명명(`isVerified()` → `verified`)과 어긋난다.
  **직렬화되는 프로퍼티에서만 발생한다** — 요청 DTO(`LoginRequest`·`SignupRequest`)는 역직렬화 시
  **생성자 파라미터 이름**(`autoLogin`)을 쓰기 때문에 영향이 없었다. 그래서 `AuthControllerTest` 20건은
  전부 통과했고, 응답을 검증하는 `EmailVerificationControllerTest` 2건만 깨졌다.

  이 비대칭성 때문에 **"요청 DTO 가 통과했으니 응답도 괜찮겠지"라고 넘어가면 놓친다.**

- **호환성을 유지한 방법**
  - Java 호출부용: `@get:JvmName("isXxx")`
  - 직렬화되는 프로퍼티는 **추가로** `@get:JsonProperty("verified")` 로 JSON 이름을 명시 고정

    ```kotlin
    @get:JvmName("isVerified")
    @get:JsonProperty("verified")
    val verified: Boolean
    ```
  - 실제 Java 호출부
    - `AuthController.login()` — `AuthController.java:103`, `request.isAutoLogin()`
    - `AuthService.signup()` — `AuthService.java:96,99`, `request.isTermsAgreed()` / `request.isPersonalInfoCollectionAgreed()`
    - `EmailVerificationServiceTest.confirm...()` — `EmailVerificationServiceTest.java:147,187`, `response.isVerified()`

- **검증 방법**
  - JSON 문자열 **완전 일치** 검증 (값은 더미):
    `assertThat(json).isEqualTo("{\"accessToken\":\"sample-access-token\"}")` 형태로 필드명까지 고정
  - `isVerified` 라는 필드가 **생기지 않았는지** 역방향 검증: `assertThat(confirm).doesNotContain("isVerified")`
  - 리플렉션으로 `getAutoLogin`/`getVerified` 가 생기지 않았는지 확인
  - `AuthKotlinInteropCompatibilityTest.JsonContract` 4건 + `PlainDtos.booleanGetterNamesUnchanged`

- **선택하지 않은 대안과 이유**

  | 대안 | 버린 이유 |
  |---|---|
  | 프로퍼티를 `isVerified` 로 명명 | **JSON 필드가 `isVerified` 로 바뀐다.** 프론트·모바일 클라이언트가 전부 깨진다. |
  | Java 호출부를 `getVerified()` 로 수정 | 이후 단계(PR D·E) 대상 파일을 미리 건드린다. PR 경계 위반. |
  | 클래스에 `@JsonNaming` 적용 | 한 프로퍼티 문제에 클래스 전체 규칙을 바꾸는 과잉 대응. |

- **최종 결정**
  1. Java 호출부가 `isXxx()` 를 쓰는 boolean 프로퍼티는 **`@get:JvmName` 으로 getter 이름을 유지**한다.
     프로퍼티 이름 자체를 `isXxx` 로 바꾸는 우회는 JSON 필드명까지 바꾸므로 쓰지 않는다.
  2. **JSON 필드명은 반드시 테스트로 고정한다.** 값이 있는지뿐 아니라 **이상한 필드가 생기지 않았는지**까지 확인한다.

  ⚠️ **일반화 주의 — 이 프로젝트에서 검증한 범위**
  `@get:JvmName` 을 붙였다고 해서 항상 `@get:JsonProperty` 가 필요한 것은 **아니다.**
  이번에 확인된 사실은 다음 범위에 한정된다.

  | 대상 | 방향 | `@get:JsonProperty` 필요 여부 | 근거 |
  |---|---|---|---|
  | `EmailVerificationConfirmResponse.verified` | **직렬화**(응답) | ✅ **필요했다** | 없이는 `$.data.verified` 가 사라져 테스트 2건 실패 |
  | `LoginRequest.autoLogin`, `SignupRequest.termsAgreed` / `personalInfoCollectionAgreed` | **역직렬화**(요청) | ❌ 불필요했다 | 생성자 파라미터 이름으로 바인딩. `AuthControllerTest` 20건 통과 |

  즉 **직렬화되는 프로퍼티에서 문제가 관측됐고, 역직렬화 전용 프로퍼티에서는 관측되지 않았다.**
  다른 Jackson 설정·다른 프로퍼티 형태까지 일반화하지 않는다. 새로 `@get:JvmName` 을 쓸 때는
  **그 프로퍼티의 JSON 필드명을 테스트로 직접 확인**하고, 어긋날 때만 `@get:JsonProperty` 를 붙인다.

---

## `protected` 무인자 생성자를 가진 Java Bean — `OAuthLoginRequest`

- **Kotlin 변환 패키지**: `com.dongnemarket.auth.dto`
- **변환 대상**: `OAuthLoginRequest` 1개 (**다른 DTO 12개와 구조가 다른 유일한 예외**)

- **Java 기존 형태** — `javap -p` 로 확인한 정본

  ```
  public class com.dongnemarket.auth.dto.OAuthLoginRequest {   ← final 아님
    private java.lang.String code;
    private java.lang.String state;
    protected com.dongnemarket.auth.dto.OAuthLoginRequest();   ← public 생성자 0개
    public java.lang.String getCode();
    public java.lang.String getState();
  }
  ```
  Jackson 이 **무인자 생성자로 인스턴스를 만든 뒤 private 필드에 직접 주입**하는 구조다.

- **Kotlin 변환 형태**

  ```kotlin
  open class OAuthLoginRequest protected constructor() {
      @field:NotBlank private var code: String? = null
      @field:NotBlank private var state: String? = null
      open fun getCode(): String? = code
      open fun getState(): String? = state
  }
  ```

- **Java 대비 Kotlin 변환 시 가지는 이점**
  - 솔직히 **이 케이스는 이점이 거의 없다.** 원본 Java 구조를 거의 그대로 옮긴 형태이고
    다른 DTO 처럼 간결해지지도 않는다. 얻는 것은 "언어 통일" 하나뿐이다.

- **변환 시 발생할 수 있는 문제점**
  - ⚠️ **Kotlin `final` class 는 `protected` 멤버를 가질 수 없다.** 그래서 원본 구조를 그대로 옮기려면
    클래스에 `open` 을 붙여야 한다.
  - 다른 DTO 처럼 주 생성자 프로퍼티로 옮기면 **원본에 없던 `public OAuthLoginRequest(String, String)` 이
    공개 API 에 추가**된다.
  - `open` 을 붙이면 상속 가능해진다 — Kotlin 관례상 바람직하지 않지만, **원본 Java 클래스도 `final` 이 아니었다.**

- **실제 프로젝트에서 발견한 문제 — 공개 생성자 확대** 🟠

  **초기 시도 (실패 사례 — 최종 구현이 아니다)**: 다른 DTO 와 똑같이 주 생성자 프로퍼티로 옮겼다.
  기능·테스트는 전부 통과했지만
  `javap -public` 비교에서 **원본에 없던 public 2인자 생성자**가 드러났다.

  ```
  before: (public 생성자 0개)
  after : public com.dongnemarket.auth.dto.OAuthLoginRequest(java.lang.String, java.lang.String);
  ```

  테스트로는 절대 안 잡히는 종류의 차이다 — **JVM 시그니처 비교가 아니었으면 그대로 넘어갔다.**
  순수 언어 전환 PR 에서 공개 표면이 넓어지는 것은 "동작은 같으니 괜찮다"로 넘길 문제가 아니다.
  한 번 공개되면 되돌릴 때 breaking change 가 된다.

- **호환성을 유지한 방법**
  원본 Java 구조를 1:1 로 옮겼다: `open class` + `protected constructor()` + `private var` 필드 + 명시적 getter.
  `open` 은 우회가 아니라 **원본이 `final` 이 아니었으므로 오히려 원본과 같은 표면**이다.

  부수 효과로 얻은 증거: 이 변경으로 깨진 기존 코드가 **0건**이었다(고친 것은 직전 커밋에서 내가 추가한
  호환성 테스트 2줄뿐). 즉 **운영 코드에 이 생성자를 쓰는 곳이 애초에 없었다**는 직접 증거다.

- **검증 방법**

  ```
  $ diff <원본 javap -p> <변환 후 javap -p>
    (출력 없음 — 완전 일치)
  ```
  원본 `.java` 를 `git show origin/develop:...` 로 꺼내 스크래치패드에서 독립 컴파일해 baseline 을 만들었다.
  테스트: `OAuthLoginRequestConstructorSurface` 5건 —
  `getConstructors()` 가 비었는지 / 선언 생성자가 protected 무인자 1개뿐인지 / 클래스가 `final` 이 아닌지 /
  Jackson 역직렬화 / 누락·null 필드 처리.

- **선택하지 않은 대안과 이유**

  | 대안 | 버린 이유 |
  |---|---|
  | 주 생성자 프로퍼티 + public 생성자 추가 수용 | 순수 언어 전환 PR 에서 공개 API 확대. 되돌리기 어려워진다. |
  | `class` + `private constructor()` | public 생성자는 안 생기지만 가시성이 protected → private 로 **바뀐다**. `final` 이라 실질 차이는 없지만 "완전 일치"는 아니다. 이왕 맞출 수 있으면 정확히 맞춘다. |
  | 다른 DTO 도 전부 이 구조로 통일 | 나머지 12개는 원본에 public 생성자가 있어 주 생성자 프로퍼티로 옮겨도 표면이 안 넓어진다. 통일하면 **불필요하게 12개를 장황하게** 만든다. |

- **최종 결정**
  **원본의 생성자 가시성을 기준으로 변환 형태를 정한다.** 원본에 public 생성자가 있으면 주 생성자 프로퍼티,
  없으면 원본 구조를 그대로 옮긴다. DTO 라고 해서 일괄로 같은 형태를 쓰지 않는다.
  일관성보다 **표면 보존**이 우선이다(단, 왜 다른지 코드 주석에 남긴다).

---

## Java static factory → `companion object` + `@JvmStatic`

- **Kotlin 변환 패키지**: `com.dongnemarket.auth.dto`
- **변환 대상**: `AccessTokenResponse.of`, `LoginResponse.of`, `TokenResponse.of`, `SignupResponse.from` (4개)

- **Java 기존 형태**: private 생성자 + `public static X of(...)`

- **Kotlin 변환 형태**

  ```kotlin
  class AccessTokenResponse private constructor(val accessToken: String?) {
      companion object {
          @JvmStatic
          fun of(accessToken: String?): AccessTokenResponse = AccessTokenResponse(accessToken)
      }
  }
  ```

- **Java 대비 Kotlin 변환 시 가지는 이점**: private 생성자 + 팩토리 관용구가 그대로 표현된다.

- **변환 시 발생할 수 있는 문제점**
  - ⚠️ `@JvmStatic` 이 없으면 Java 호출부가 **`AccessTokenResponse.Companion.of(...)`** 로 바뀌어야 한다. 전부 깨진다.
  - `Companion` 이라는 **public static 필드가 추가**된다(순수 additive).
  - `@JvmStatic` 이 만드는 메서드는 `public static` 이 아니라 **`public static final`** 이다.
    시그니처 비교 시 정규화하지 않으면 오판한다.
  - `SignupResponse.from(Member)` 는 아직 Java 인 `Member` 를 받는다. `member.getId()` 는 플랫폼 타입이라
    **non-null `Long` 로 조이면 자동 언박싱 NPE** 위험이 있다(PR #17 이 이미 겪은 문제).

- **실제 프로젝트에서 발견한 문제**
  `javap` 비교 1차 스크립트가 `public final ` 만 정규화하고 `public static final ` 을 놓쳐
  **정적 팩토리 4개를 "사라졌다"고 오판**했다. 정규화 규칙을 고쳐 재검증한 뒤 전부 보존됨을 확인했다.
  → 검증 도구 자체도 검증해야 한다는 교훈.

- **호환성을 유지한 방법**: 4개 전부 `@JvmStatic`. `SignupResponse` 의 `memberId` 는 `Long?` 유지.
- **검증 방법**: `javap -public` 에 `public static final ... of(java.lang.String);` 존재 확인 +
  `PlainDtos.constructorsAndFactoriesUnchanged` 에서 실제 Java 호출.
- **선택하지 않은 대안과 이유**: 최상위 함수(top-level) — Java 에서 `XxxKt.of(...)` 로 보여 호출부가 깨진다.
- **최종 결정**: **Java 에서 호출되는 정적 팩토리에는 예외 없이 `@JvmStatic`.**

---

## Java interface → Kotlin interface

- **Kotlin 변환 패키지**: `com.dongnemarket.auth.mail`
- **변환 대상**: `EmailSender`
- **Java 기존 형태**: 단일 추상 메서드 `void send(String to, String subject, String content)`
- **Kotlin 변환 형태**: 일반 `interface` (`fun interface` 아님)
- **Java 대비 이점**: 거의 없음. 언어 통일 목적.
- **변환 시 발생할 수 있는 문제점**
  - `fun interface` 로 만들면 **SAM 변환이 새로 생긴다** — 원본 Java 인터페이스는 Kotlin 쪽에서 이미 SAM 변환이
    되지만, `fun interface` 는 여기에 더해 Kotlin 구현체에도 람다 대입을 허용한다. 원본에 없던 사용 방식이다.
  - 구현체 `SmtpEmailSender` 는 아직 Java 다. Kotlin 인터페이스를 Java 가 구현하는 방향은 문제없다.
- **실제 프로젝트에서 발견한 문제**: 없음. `javap -public` **완전 일치**(18개 중 유일하게 diff 0).
- **호환성을 유지한 방법**: 파라미터 타입을 non-null `String` 으로 두되 시그니처는 동일.
- **검증 방법**: `javap -public` diff 0. `SmtpEmailSenderTest` 통과.
- **선택하지 않은 대안과 이유**: `fun interface` — 위 사유.
- **최종 결정**: **인터페이스는 기본형으로 옮긴다.** `fun interface` 는 실제 람다 사용처가 있을 때만.

---

## 예외 항목: `OAuthAuthorizationState.oidcNonce` — nullability 를 조여서 카카오 로그인이 깨진 사례 🔴

특정 파일에서만 발생했지만 이 마이그레이션 전체에서 **가장 위험했던 문제**라 별도로 기록한다.

- **Kotlin 변환 패키지**: `com.dongnemarket.auth.repository`
- **변환 대상**: `OAuthAuthorizationState` 의 참조형 컴포넌트 6개

- **Java 기존 형태**: Java `record` 는 컴포넌트에 **null 검사를 넣지 않는다.** 전부 null 허용.

- **Kotlin 변환 형태 — 초기 시도 (실패 사례, 최종 구현이 아니다)**

  ```kotlin
  @JvmRecord data class OAuthAuthorizationState(
      val provider: OAuthProvider,      // non-null
      val oidcNonce: String,            // non-null  ← 여기서 터졌다
      ...
  )
  ```

- **Java 대비 Kotlin 변환 시 가지는 이점**
  - nullability 가 타입에 드러나므로, **auth 도메인이 전부 Kotlin 이 된 뒤에는** null 가능성이
    컴파일 타임에 강제된다(`oidcNonce` 를 검증 없이 쓰면 컴파일 에러). Java 에서는 문서에만 있던 계약이다.
  - ⚠️ 다만 그 이점은 **호출부가 전부 Kotlin 이 된 뒤에야** 생긴다. 전환 도중에는 오히려 아래의 위험만 남는다.

- **변환 시 발생할 수 있는 문제점**
  Kotlin 은 non-null 파라미터에 **런타임 null 검사(`Intrinsics.checkNotNullParameter`)를 삽입**한다.
  Java 호출부는 이걸 컴파일 타임에 알 수 없다. 즉 **컴파일은 멀쩡히 통과하고 런타임에 NPE 로 죽는다.**

- **실제 프로젝트에서 발견한 문제**

  ```
  java.lang.NullPointerException: Parameter specified as non-null is null:
    method com.dongnemarket.auth.repository.OAuthAuthorizationState.<init>, parameter oidcNonce
  ```

  **테스트 14건이 한꺼번에 깨졌다** (`AuthServiceTest` 12건 + `EmailVerificationControllerTest` 2건).
  게다가 Mockito 스텁 블록 안에서 NPE 가 나 **`UnfinishedStubbingException`(AuthServiceTest:705)** 라는
  전혀 무관해 보이는 2차 오류까지 나왔다 — 원인 추적을 어렵게 만드는 전형적인 형태다.

  근본 원인은 코드에 명확히 있었다.

  ```java
  // AuthService.java:179 — 카카오는 서명 ID Token 을 쓰지 않으므로 nonce 가 null 이다
  String oidcNonce = client.provider() == OAuthProvider.GOOGLE ? generateUrlSafeRandom(32) : null;
  ```

  게다가 `RedisOAuthStateRepository` 는 **null 을 1급 값으로 다루고 있었다**:
  저장 시 `nullToEmpty(value.oidcNonce())`, 복원 시 `oidcNonce.isEmpty() ? null : oidcNonce`.
  즉 null 은 실수가 아니라 **설계된 계약**이었다.

  ⚠️ 주의할 점: 원본 Javadoc 에는 *"카카오는 … 빈 문자열"* 이라고 적혀 있었지만 **실제 코드는 `null`** 이었다.
  **문서가 아니라 코드와 테스트를 근거로 판단해야 한다.**

- **호환성을 유지한 방법**
  참조형 컴포넌트를 **전부 nullable 로 되돌렸다.** Java record 가 null 검사를 하지 않았으므로
  nullable 이 원본에 충실한 번역이다. `javap -public` 상 descriptor 는 그대로다(`java.lang.String`).

- **검증 방법**
  - `AuthServiceTest` 12건 + `EmailVerificationControllerTest` 2건 복구 → 625건 전부 통과
  - 회귀 고정 테스트: `JvmRecords.oidcNonceAcceptsNull` —
    `new OAuthAuthorizationState(KAKAO, ..., null, ...)` 가 예외 없이 생성되는지 확인

- **선택하지 않은 대안과 이유**

  | 대안 | 버린 이유 |
  |---|---|
  | `oidcNonce` 만 nullable, 나머지는 non-null | 다른 컴포넌트의 non-null 을 **증명할 수 없다.** 테스트가 안 건드리는 경로에 null 이 있으면 같은 방식으로 런타임에 죽는다. 조여서 얻는 이득(PR A 시점에 Kotlin 호출부 0개)보다 위험이 크다. |
  | Javadoc 대로 빈 문자열로 통일 | **기능(동작) 변경**이다. Redis 저장 포맷과 `AuthService` 분기가 함께 바뀐다. 언어 전환 PR 에 넣으면 안 된다. |
  | `lateinit` / `!!` | 문제를 미루기만 하고 같은 NPE 를 다른 지점으로 옮긴다. |

- **최종 결정**
  **공개 API 의 nullability 는 전환 시점에 조이지 않는다.** 원본 시그니처를 그대로 옮기고,
  해당 도메인이 **전부 Kotlin 이 된 뒤 별도 패스**에서 조인다(`backend.md` 규칙, PR #17 이 이미 같은 결론).
  이유: 호출부가 Java 인 동안에는 **컴파일러가 아무 경고도 주지 않아 런타임까지 문제가 숨는다.**

---

## PR A 요약표

| Kotlin 변환 패키지 | Java 형태 | Kotlin 형태 | 이점 | 위험 | 호환 방법 | 검증 |
|---|---|---|---|---|---|---|
| `auth.client` `auth.dto` `auth.repository` | `record` 3 | `@JvmRecord data class` | record 접근자 유지, 구조 분해 | 일반 data class 로 바꾸면 접근자 27곳 붕괴 | `@JvmRecord` | `javap` `isRecord()` / JvmRecords 4건 |
| `auth.dto` | 일반 Bean 13 | 일반 `class` | 보일러플레이트 제거 | data class 시 equals·copy·componentN·toString 신규 발생(토큰 노출) | 일반 class 유지 | equals 선언 클래스가 Object 인지 / PlainDtos 5건 |
| `auth.dto` | `boolean isXxx()` 4 | `@get:JvmName` + `@get:JsonProperty` | 필드/getter 쌍 제거 | 프로퍼티명을 `isXxx` 로 하면 **JSON 계약 붕괴** | JvmName + JsonProperty 세트 | JSON 완전 일치 / JsonContract 4건 |
| `auth.dto` | protected 무인자 Bean 1 | `open class` + `protected constructor()` | (없음, 표면 보존 목적) | 주 생성자 프로퍼티 시 public 생성자 확대 | 원본 구조 1:1 이식 | `javap -p` **diff 0** / ConstructorSurface 5건 |
| `auth.dto` | static factory 4 | `companion object` + `@JvmStatic` | 팩토리 관용구 유지 | `@JvmStatic` 누락 시 `.Companion.` 필요 | `@JvmStatic` | `javap` `public static final` 확인 |
| `auth.mail` | interface 1 | `interface` | — | `fun interface` 는 SAM 신규 발생 | 기본형 유지 | `javap` **완전 일치** |
| `auth.entity` | `enum` 1 | `enum class` | — | `getEntries()` 추가(additive) | — | `javap` |
| **예외**: `auth.repository` | `record` nullable 컴포넌트 | nullable 유지 | — | 🔴 **non-null 화 시 카카오 로그인 런타임 NPE** | 전 컴포넌트 nullable | 테스트 14건 복구 / oidcNonceAcceptsNull |

**PR A 결과**: `test` 648건(기존 625 + 신규 23) · `integrationTest` 44건 · 전부 통과.
JVM 공개 멤버 손실 0건. 운영 service/controller 코드 변경 0건.

> ⚠️ 위 "공개 멤버 손실 0건" 은 **`javap -public` 기준**이라 `protected` 멤버를 포함하지 않는다.
> 이후 `javap -p` 로 전수 재비교했을 때 요청 DTO 6개의 `protected` 무인자 생성자 손실이 드러났다 —
> 아래 「보호 생성자」절 참고. 검증 기준은 그 시점부터 `javap -p` 다.

---

# 보호 생성자 — `javap -public` 의 검증 공백과 복원

PR A 직후 발견해 같은 브랜치에서 고친 건이라 PR A 기록 바로 뒤에 붙인다.
대상은 요청 DTO 6개: `LoginRequest` · `SignupRequest` · `EmailVerificationRequest` ·
`EmailVerificationConfirmRequest` · `PasswordResetRequest` · `PasswordResetConfirmRequest`.

## 검증 공백 — `javap -public` 은 protected 를 출력하지 않는다

원본 Java 요청 DTO 는 전부 이런 모양이었다.

```java
public class LoginRequest {
    private String email;
    protected LoginRequest() { }                       // ← 이것
    public LoginRequest(String email, String password) { ... }
}
```

Kotlin 으로 옮기며 주 생성자 프로퍼티 방식을 쓰자 `protected` 무인자 생성자가 사라졌다.
그런데 **PR A 의 검증은 이 손실을 잡지 못했다.**

`javap -public` 은 이름 그대로 **public 멤버만** 출력한다. `protected LoginRequest()` 는
변환 전 출력에도, 변환 후 출력에도 찍히지 않는다. 두 파일 모두에 없으니 diff 는 깨끗했다.
`javap -p`(private·protected 포함)를 쓴 대상은 `OAuthLoginRequest` 하나뿐이었고, 그 클래스는
**public 표면이 넓어지는** 반대 방향의 문제라 눈에 띄었을 뿐이다.

> **교훈**: 검증 도구가 "무엇을 보여주지 않는지" 를 먼저 확인한다. 통과한 diff 가
> "차이가 없다" 는 뜻인지 "그 차이를 볼 수 없었다" 는 뜻인지는 도구의 옵션이 결정한다.
> 이 마이그레이션의 표면 판정 기준을 **`javap -p` 전수 비교**로 올린다.
> `javap -public` 은 보조 자료로만 둔다.

## 발견 경위

최신 `origin/develop` 임시 병합 검증 중, 지정 항목(`OAuthLoginRequest`) 외에 **PR A 변환 대상
18개 전체**를 `javap -p` 로 훑으면서 드러났다. 6개 클래스에서 동일한 형태로 빠져 있었다.

## 왜 고쳤나 — 사용처가 없는데도

먼저 영향도를 실측했다.

| 항목 | 결과 |
|---|---|
| 이 6개를 상속하는 클래스 | **0건** |
| `@ModelAttribute` 사용처(무인자 생성자 필요) | **0건** |
| 사용 형태 | 전부 `@RequestBody` — Jackson 이 주 생성자로 역직렬화 |
| 기존 테스트 | 648건 + integrationTest 44건 전부 통과 |

**기능 영향은 없다.** 그럼에도 복원한 이유:

1. 이 PR 은 **기능 변경이 0 인 순수 언어 전환**이다. `protected` 생성자도 원본 JVM 계약의
   일부고, "지금 아무도 안 쓴다" 는 계약을 임의로 좁혀도 된다는 근거가 아니다.
2. **기준의 일관성.** 같은 PR 이 `OAuthLoginRequest` 에서는 표면이 *넓어지는* 것을 막으려고
   커밋을 따로 냈다. 반대 방향(좁아지는 것)만 눈감으면 기준이 서지 않는다.
3. **선례.** 남은 2~7단계는 entity·repository·service 로 갈수록 표면 판단이 어렵고 사용처
   조사도 부정확해진다. 여기서 "사용처 없으면 지워도 된다" 를 허용하면 그 기준이 그대로 따라간다.

### 선택하지 않은 대안 — A안: "영향 없음으로 기록하고 제거 유지"

| | 내용 |
|---|---|
| 내용 | 코드는 그대로 두고 PR 본문·문서에 "protected 생성자 6건 제거, 사용처 0건이라 영향 없음" 만 기록 |
| 장점 | 코드 변경 0. Kotlin 관용구(주 생성자 프로퍼티)를 그대로 유지 |
| **버린 이유** | 이번 한 번은 안전하지만 위 3번 그대로 **선례가 남는다.** 또 "영향 없음" 의 근거가 *현재 코드베이스 조사*뿐이라, 나중에 누가 상속하거나 `@ModelAttribute` 를 쓰면 조용히 깨진다. 순수 전환 PR 에서 표면을 좁힐 이유로는 약하다 |

## 어떻게 고쳤나 — 주 생성자 유지, 보조 생성자만 복원

DTO 전체를 mutable Java Bean 구조로 되돌리지 않았다. 주 생성자 프로퍼티는 그대로 두고
**보조 생성자만 추가**하는 최소 변경이다.

```kotlin
open class EmailVerificationRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    open val email: String?,
) {
    protected constructor() : this(null)
}
```

| 요소 | 이유 |
|---|---|
| `protected constructor() : this(null, ...)` | 원본 무인자 생성자가 남기던 필드 상태를 그대로 재현한다. String 은 `null`, primitive boolean 은 `false` — **새로 정한 기본값이 아니라 JVM 필드 기본값 그대로**라 도메인 의미를 만들지 않는다 |
| `open class` | Kotlin 은 final 클래스에 `protected` 멤버를 두지 못한다. 원본 Java 클래스도 final 이 아니었으므로 원본과 같은 표면이다(`OAuthLoginRequest` 와 동일한 판단) |
| `open val` | 원본 Java getter 는 오버라이드 가능했다. Kotlin 프로퍼티 접근자는 기본 `final` 이라 명시한다 |

하지 않은 것: `@JvmOverloads` 신규 부착 · public 생성자 추가 · `var` 전환(원본에 없던 setter 가
생겨 표면이 오히려 넓어진다) · JSON/Validation 계약 변경.

### boolean getter 3개는 `final` 로 남는다 🔴 — Kotlin 하드 제약

`isAutoLogin` · `isTermsAgreed` · `isPersonalInfoCollectionAgreed` 에는 `open` 을 붙이지 못했다.

```
e: '@JvmName' annotation is not applicable to this declaration.
```

Kotlin 은 **`@JvmName` 을 open 멤버에 금지한다** — 이름을 바꾼 getter 를 오버라이드 가능하게 두면
가상 디스패치가 깨지기 때문이다. 둘 중 하나만 가질 수 있고 `@get:JvmName` 을 택했다.

- 이름을 잃으면 `AuthController` 의 `isAutoLogin()` 호출부와 **JSON 필드명이 함께 깨진다**
  (PR A 의 회귀 1번과 같은 종류의 사고).
- getter 오버라이드 가능성은 실제로 쓰는 곳이 없다.

즉 이 3개의 finality 차이는 **선택이 아니라 언어 제약**이다.

## 수정 후 최종 결과

**bytecode (`javap -p`, 6개 전부)**

| 항목 | 결과 |
|---|---|
| 클래스 선언 | `public class …`(non-final) — **원본과 일치** |
| `protected` 무인자 생성자 | **1:1 복원** |
| public 생성자 개수(synthetic 제외) | `LoginRequest` 2 · 나머지 1 — **원본과 동일** |
| String getter | non-final — **원본과 일치** |
| boolean getter | `final` — 위 Kotlin 제약 |
| private 필드 | `private final`(원본 `private`) — `val` 의 결과 |

**PR A 변환 대상 18개 전수 `javap -p` 재비교: 이름+descriptor 기준 멤버 손실 0건.**

**Jackson** — 무인자 생성자가 생겼다고 Jackson 이 "무인자 + 필드 주입" 경로로 갈아타면
final 필드 때문에 값이 조용히 비게 된다. 실제 값이 채워지는지 직접 확인했다:
정상 body · 필드 누락 · 명시적 `null` · 빈 문자열 · boolean 누락/명시값 전부 원본과 동일.

**Validation** — 역직렬화 성공과 Validation 실패가 분리돼 있고, 제약 메시지·대상 필드가 원본과
같다. **`protected` 무인자로 만든 인스턴스도 동일하게 위반이 검출된다**(복원이 우회 경로를 만들지 않았다).

**테스트** — 호환성 테스트 14건 추가(23 → 37건), `test` 662건(648 + 14) 전부 통과.

---

# 응답 DTO finality — 같은 검사에서 이어서 나온 차이

위 「보호 생성자」건으로 검증 기준을 `javap -p` 전수 비교로 올린 직후, 같은 검사에서 응답 DTO 에도
같은 종류의 차이가 남아 있는 것이 드러났다. 대상 6개:
`AccessTokenResponse` · `LoginResponse` · `TokenResponse` · `SignupResponse` ·
`EmailVerificationResponse` · `EmailVerificationConfirmResponse`.

```
원본  : public class AccessTokenResponse {   public java.lang.String getAccessToken();
전환후: public final class AccessTokenResponse {   public final java.lang.String getAccessToken();
```

Kotlin 은 클래스와 프로퍼티 접근자가 기본 `final` 이고, Java 는 기본 non-final 이다.
전환만 했는데 **표면이 조용히 좁아진** 것이다.

## 사용처 유무와 무관하게 복원한 이유

이 6개는 서버→클라이언트 **응답 전용**이라 상속하는 코드도, 역직렬화 경로도 없다.
그럼에도 복원한 근거는 「보호 생성자」절과 같다.

1. 이 PR 은 **기능 변경이 0 인 순수 언어 전환**이다. 클래스·getter 의 non-final 여부도 원본
   JVM 계약의 일부다.
2. "지금 상속하는 곳이 없다" 는 계약을 좁혀도 된다는 근거가 아니라, **아직 문제가 드러나지
   않았다**는 뜻일 뿐이다.
3. **기준의 반쪽화 방지.** 요청 DTO 만 맞추고 응답 DTO 를 남겨두면, 남은 2~7단계에서 "어느 쪽
   기준을 따르나" 라는 질문이 매번 되살아난다.

## `open class` + `open val` — 그 외에는 손대지 않았다

```kotlin
open class AccessTokenResponse private constructor(
    open val accessToken: String?,
) {
    companion object {
        @JvmStatic
        fun of(accessToken: String?): AccessTokenResponse = AccessTokenResponse(accessToken)
    }
}
```

| 지킨 것 | 이유 |
|---|---|
| 일반 `class` 유지(`data class` 아님) | `equals`/`hashCode`/`toString`/`componentN`/`copy` 가 새로 생기면 원본에 없던 동작이다. 특히 `TokenResponse`·`LoginResponse` 는 **토큰 값을 들고 있어 `toString()` 이 로그로 값을 흘릴 위험**이 있다 |
| 생성자 표면 불변 | private 생성자 4개는 여전히 public 이 아니고, public 생성자 2개는 그대로 1개씩이다 |
| `@JvmStatic` 정적 팩토리 유지 | 없으면 Java 호출부가 `.Companion.of(...)` 가 된다 |
| JSON 필드명·nullability 불변 | 계약 변경 금지 |

> `open class` 인데 생성자가 `private` 이면 실제로는 상속할 수 없다. **원본 Java 도 정확히 같은
> 상태였다**(`public class` + `private` 생성자). 표면만 원본과 같아지는 것이고, 그게 목적이다.

### `var` 전환을 버린 이유

`val` → `var` 로 바꾸면 backing field 의 `final` 이 떨어져 나가 원본과 더 가까워 보인다.
그러나 **Kotlin 이 public setter(`setAccessToken(String)`)를 함께 만든다.** 원본에 없던 공개
메서드가 생기는 것이라, private 필드 수식어 하나를 맞추려고 **공개 표면을 넓히는 교환**이 된다.
표면 보존이 목적인 작업에서 방향이 정반대다. 그래서 쓰지 않았고, 회귀 테스트
(`noPublicSettersAnywhere`)로 13개 DTO 전체에 setter 가 0건임을 고정했다.

### private 필드는 손댈 것이 없었다

요청 DTO 와 달리 **원본 응답 DTO 는 필드가 이미 `private final`** 이었다(`javap -p` 확인).
이쪽은 필드 수식어 차이가 애초에 존재하지 않는다.

### `EmailVerificationConfirmResponse.verified` 는 `final` 로 남는다 🔴

`@get:JvmName("isVerified")` 와 `open` 은 함께 쓸 수 없다 — Kotlin 이 금지한다.

```
e: '@JvmName' annotation is not applicable to this declaration.
```

이름을 바꾼 getter 를 오버라이드 가능하게 두면 가상 디스패치가 깨지기 때문이다.
`isVerified()` 이름(Java 호출부)과 `@get:JsonProperty("verified")` 로 고정한 JSON 필드명이
오버라이드 가능성보다 우선한다. 요청 DTO 의 boolean getter 3개와 **같은 제약**이다.
같은 클래스의 `email` 프로퍼티는 제약이 없어 `open` 으로 복원했다.

## 최종 결과 — 남은 허용 차이 목록

18개 전체 `javap -p` 재비교 기준. **클래스 선언 finality 불일치 0/18**,
**이름+descriptor 기준 멤버 손실 0/18**, **public setter 0건**.

완전 일치: `OAuthLoginRequest` · `EmailSender` · `EmailVerificationResponse` (3개)

남은 차이는 전부 아래 셋 중 하나이며, **기존 멤버가 사라진 경우는 없다.**

| 분류 | 항목 | 왜 남는가 |
|---|---|---|
| **① 언어 제약 — 복원 불가** | boolean getter 4개의 `final`<br>(`isAutoLogin` · `isTermsAgreed` · `isPersonalInfoCollectionAgreed` · `isVerified`) | Kotlin 이 `@JvmName` + `open` 병용을 금지한다. 이름 유지가 우선 |
| **① 언어 제약 — 복원 불가** | `@JvmStatic` 정적 팩토리가 `public static final`<br>(원본 `public static`) | Kotlin 이 `@JvmStatic` 에 항상 `ACC_FINAL` 을 붙인다. static 메서드는 어차피 오버라이드 대상이 아니라 **의미상 차이가 없는 플래그 차이**다 |
| **② Kotlin 필수 synthetic** | `DefaultConstructorMarker` 생성자 6개 · `copy$default` 3개 · enum `$VALUES`/`$values()`/`$ENTRIES` | `ACC_SYNTHETIC` 확인됨. **Java 소스에서 호출할 수 없다** |
| **② Kotlin 구조 필수** | `Companion` 필드 + `static {}` (companion object 4개) · `getEntries()` (Kotlin 2.x enum) · `componentN()`/`copy()` (`@JvmRecord data class` 3개) | 언어 구조상 따라오는 것. 전부 additive |
| **③ 의도적으로 유지한 차이** | **요청 DTO 6개의 private backing field 가 `private final`**(원본 `private`) | `var` 로 바꾸면 원본에 없던 **public setter** 가 생긴다. private 필드 수식어보다 공개 표면 보존이 우선이다. private 이라 어떤 호출부에도 보이지 않는다 |

③은 이 한 항목뿐이고, 나머지는 전부 복원됐거나 언어가 강제하는 차이다.

**테스트** — 호환성 테스트 5건 추가(37 → 42건), `test` 667건(662 + 5) · `integrationTest` 44건 통과.

> ⚠️ 이 시점의 최신 `origin/develop`(`db7b8d7`)은 이 브랜치와 무관한 이유로 컴파일 실패
> 상태였다(`favorite/dto/FavoriteProductSummary.kt` — Category Kotlin 전환 후의 도메인 간
> nullability 회귀). 위 결과는 **feature 브랜치 단독 실행 결과**이며, develop 복구 후
> 임시 병합 검증을 다시 수행해야 한다.

---

# 이후 단계 계획 — 5단계(A~E)에서 7단계로 재분할 *(예정 — 미착수)*

> 아래 절 전체는 **확정된 구현 기록이 아니라 검토 예정 항목 목록**이다.
> 각 단계를 실제로 수행한 뒤 위 PR A 와 같은 양식(11개 필드)으로 이 문서에 누적한다.
> 여기 적힌 어떤 선언 형태·수치도 **아직 실측된 것이 아니다.**

당초 계획은 A~E 5단계였다. 최신 `origin/develop` 기준으로 대상 파일을 실제로 집계한 결과
Persistence(구 PR B, 21파일 1,082줄)와 Service(구 PR D, 7파일 831줄)가 한 PR 로 감당하기에 커서
**7단계로 재분할**했다.

| # | 브랜치 | 대상 | 상태 |
|---|---|---|---|
| 1 | `feature/auth_kotlin_model` | 기반 타입 18 | **완료(위 PR A 기록) — 머지 대기** |
| 2 | `feature/auth_kotlin_persistence_entity` | entity 3 + JPA repository 4 | 예정 |
| 3 | `feature/auth_kotlin_persistence_store` | 저장소 추상화 5 + Redis 5 + InMemory 4 | 예정 |
| 4 | `feature/auth_kotlin_oauth` | client 6 + config 1 | 예정 |
| 5 | `feature/auth_kotlin_support_service` | 지원 서비스 4 + mail 1 | 예정 |
| 6 | `feature/auth_kotlin_core_service` | `OAuthSignupTransaction` · `AuthService` | 예정 |
| 7 | `feature/auth_kotlin_controller` | controller 3 | 예정 |
| — | `feature/auth_device_session_management` | 기기·세션 관리 **기능** | 전환 완료 후 별도 |

**분기 규칙**: 각 단계는 **직전 PR 이 develop 에 병합된 뒤 최신 `origin/develop` 에서 새로 분기**한다.
stacked branch 는 쓰지 않는다 — 앞 PR 이 리뷰에서 바뀌면 뒤에 쌓은 브랜치를 전부 다시 만들어야 하고,
CI 가 없어(위 「환경」) **각 PR 을 develop 기준으로 로컬 검증하는 것이 유일한 게이트**이기 때문이다.

**기능을 섞지 않는다**: 기기·세션 관리 기능은 1~7단계 어느 PR 에도 넣지 않는다. 전환 PR 의 가치는
"언어 변환이며 기능 변경이 0" 이라는 점에 있고, 기능이 섞이면 회귀가 났을 때 **언어 전환 탓인지
새 기능 탓인지 구분할 수 없어** 기존 테스트를 회귀 안전망으로 쓰는 전략 자체가 무너진다.

---

## 왜 Persistence 를 2·3단계로 나눴나 *(분할 사유)*

구 PR B 는 entity 3 + repository 18 = 21파일이었다. 두 덩어리는 **깨지는 방식이 서로 다르다.**

| | 2단계 (entity) | 3단계 (store) |
|---|---|---|
| 깨지는 지점 | JPA 매핑·프록시·`@JvmStatic` — **애플리케이션 기동 시점** | Redis 키·TTL·Lua — **런타임 동작** |
| 잡아내는 수단 | `ddl-auto=validate` 기동, `@DataJpaTest`, `javap` | `integrationTest`(Testcontainers Redis) |
| 외부 영향 | `member` 테스트 2개가 `EmailVerification` 참조 | 없음(auth 내부) |
| 실패 시 증상 | 컴파일 에러 또는 기동 실패(**즉시 드러남**) | 프로파일별로 갈리는 조용한 오동작(**늦게 드러남**) |

섞으면 `integrationTest` 가 깨졌을 때 원인이 JPA 쪽인지 Redis 쪽인지 좁히는 데 시간이 든다.
경계는 **"기동 시점에 드러나는 것"과 "런타임에 드러나는 것"** 으로 잡았다.
3단계는 **2단계가 develop 에 병합된 뒤에만** 시작한다(3단계의 `JpaRefreshTokenRepository` 대칭 짝이
2단계 결과 위에서만 의미가 있다).

## 왜 Service 를 5·6단계로 나눴나 *(분할 사유)*

구 PR D 는 7파일 831줄이고, 그중 `AuthService` 한 파일이 317줄로 38% 를 차지한다.

| | 5단계 (support) | 6단계 (core) |
|---|---|---|
| 대상 | `RefreshTokenService` `LoginAttemptService` `EmailVerificationService` `PasswordResetService` `SmtpEmailSender` | `OAuthSignupTransaction` `AuthService` |
| 성격 | 저장소를 감싼 **얇은 정책 계층** — 트랜잭션 경계가 단순 | **트랜잭션 경계 자체가 설계** — `NOT_SUPPORTED` 전파, 별도 빈, 동시성 |
| 외부 영향 | `MemberService.java` 가 `RefreshTokenService` 호출 | 없음(auth 내부) |
| 리뷰 초점 | fail-open/fail-closed · 상수 · JVM 시그니처 | 전파 속성 · 동시 가입 · reconcile |

`AuthService` 는 5단계 대상 4개를 **전부 주입받는다.** 지원 서비스의 JVM 시그니처를 먼저 확정해
develop 에 넣어두면, 6단계는 "호출부를 Kotlin 으로 옮기는 일" 만 남아 리뷰 초점이 트랜잭션 경계
하나로 좁혀진다. 6단계는 **5단계가 develop 에 병합된 뒤에만** 시작한다.

---

## 2단계 — Persistence Entity `feature/auth_kotlin_persistence_entity` *(예정)*

대상(7): `entity/RefreshToken` `entity/EmailVerification` `entity/MemberSocialAccount`
`repository/EmailVerificationRepository` `repository/MemberSocialAccountRepository`
`repository/RefreshTokenJpaEntityRepository` `repository/JpaRefreshTokenRepository`

검토 예정 항목:
- JPA 매핑 — `@field:Column` 등 **use-site target**(누락 시 어노테이션이 생성자 파라미터에 붙어
  **JPA 가 매핑을 조용히 무시**한다) / field access ↔ property access 차이
- `protected` 무인자 생성자 표면 — 현재 3개 entity 모두 `protected Xxx()` 를 명시 선언한다.
  noarg 플러그인이 합성하는 생성자와 **`javap -p` 상 가시성이 같은지** 확인
- **`data class` 미사용** — 지연 로딩 프록시·JPA 동일성과 어긋난다(`backend.md` 규칙)
- **identity 기반 `equals`/`hashCode` 유지** — 3개 entity 모두 현재 미정의(= `Object` 참조 동등성).
  `equals` 선언 클래스가 `Object` 인지 `javap` 로 확인
- static factory 의 Java 호출 호환 — `RefreshToken.issue` `EmailVerification.verified`
  `MemberSocialAccount.of`. **`@JvmStatic` 누락 시 `.Companion.` 이 필요해져** 아직 Java 인
  `AuthService`·`EmailVerificationService`·`member` 테스트 2개가 깨진다
- `Member`(아직 Java) entity 참조 → 아래 「표현 보정」절의 절차로 결정
- test profile 의 JPA 구현 — `JpaRefreshTokenRepository`(`@Profile("test")`) 가
  `findByMemberId` → `replace()` dirty checking → 없으면 `save()` 하는 upsert 동작을 유지하는지
- nullable DB 컬럼 ↔ Kotlin 타입 (Flyway `validate` 와 어긋나지 않게)
- **미검증 — 2단계에서 확인할 항목**: `backend.md` 에는 allOpen 이 프로퍼티도 open 으로 만들어
  `private set` 이 컴파일 에러가 된다고 기록돼 있다(PR #17 작성자 경험). PR A 는 entity 를 다루지 않아
  **직접 확인하지 못했다.** 실제 컴파일로 확인한 뒤 결과를 이 문서에 기록한다.

## 3단계 — Persistence Store `feature/auth_kotlin_persistence_store` *(예정)*

대상(14): 저장소 추상화 5 (`RefreshTokenRepository` `OAuthStateRepository` `LoginAttemptRepository`
`EmailVerificationCodeRepository` `PasswordResetTokenRepository`) + Redis 구현 5 + InMemory 구현 4

검토 예정 항목:
- **Redis/InMemory 프로파일 대칭** — 4쌍이 `@Profile("test")`/`@Profile("!test")` 로 대칭이다.
  **두 구현을 같은 커밋에서 함께 옮긴다** — 한쪽만 옮기면 `./gradlew test` 는 test 프로파일만 돌아
  **Redis 쪽 회귀를 잡지 못한다**(`integrationTest` 에서야 드러난다)
- **`Optional<T>` 반환 유지** — 호출부(5·6단계 대상)가 아직 Java 이고 `.orElseThrow` `.ifPresentOrElse`
  `.map().orElseGet()` `.filter()` 를 직접 쓴다. nullable 로 바꾸면 뒤 단계 파일을 미리 수정해야 해
  **PR 경계가 무너진다.** `Optional` → nullable 축소는 7단계 이후 별도 패스
- Redis 키와 TTL 불변 — `auth:refresh:{memberId}` · `auth:oauth:state:{state}` ·
  `auth:oauth:bcid:{hash}:states` · `auth:login:fail:{email}` · `auth:email:verify:{email}`
- Lua 스크립트 — `ISSUE_SCRIPT`/`CONSUME_SCRIPT` 를 Kotlin raw string 으로 옮길 때
  **`$` 이스케이프 필요 여부 확인**(현재 본문에 `$` 없음 → 안전해 보이나 검증 항목으로 남긴다)
- **ARGV 순서와 인덱스** — `ARGV[1..10]` 과 `IDX_PROVIDER..IDX_ISSUED_AT` 6개 상수가 바이트 단위로 동일해야 한다
- **`oidcNonce` null ↔ 빈 문자열 왕복** — 저장 시 `nullToEmpty`, 복원 시 `isEmpty() ? null`.
  PR A 에서 카카오 로그인을 무너뜨린 바로 그 계약이다(위 「예외 항목」 참조). 절대 조이지 않는다
- timeout·장애 동작 — `RedisCommandTimeoutTest` 가 고정하는 동작 유지
- `LoginAttemptRepository.incrementFailure` 의 **최초 실패에만 TTL 설정**(윈도우 미연장) 의미 보존

## 4단계 — OAuth `feature/auth_kotlin_oauth` *(예정)*

대상(7): `client/OAuthClient` `client/KakaoOAuthClient` `client/GoogleOAuthClient`
`client/GoogleIdTokenValidator` `client/OAuthAuthorizationUrlFactory` `client/OAuthClientErrorMapper`
`config/OAuthWebClientConfig`

검토 예정 항목:
- 인가 URL 쿼리 파라미터 **이름·순서** 및 scope(`account_email` / `openid email`) 완전 일치
- 외부 API 오류 매핑 — `OAuthClientErrorMapper` 의 예외 타입·발생 시점 보존
  (4xx→`OAUTH_AUTHORIZATION_FAILED`, 5xx·네트워크·timeout·`CodecException`·`DataBufferLimitException`→`OAUTH_PROVIDER_ERROR`)
- `OAuthClientErrorMapper` 는 package-private `final class` + static 이다. Kotlin 으로 옮길 때
  **`Supplier<T>` 파라미터 타입을 유지**한다 — Kotlin 함수 타입으로 바꾸면 아직 Java 인 호출부의
  SAM 변환이 깨진다. `@JvmStatic` 필요 여부 확인
- Google nonce ↔ 카카오 nonce 차이 / `MessageDigest.isEqual` **상수시간 비교 유지**
- private nested `record` 3개(`KakaoTokenResponse` `KakaoUserResponse.KakaoAccount` `GoogleTokenResponse`)
  의 Jackson 어노테이션(`@JsonNaming` `@JsonIgnoreProperties`) 보존
- `Boolean` 박싱 필드(`isEmailValid` `isEmailVerified`) 는 **nullable 유지** —
  `Boolean.TRUE.equals()` 의 3-state 의미가 사라지면 안 된다
- WebClient 응답 body nullability / MockWebServer 기반 4xx·5xx·timeout·깨진 JSON 재현

## 5단계 — Support Service `feature/auth_kotlin_support_service` *(예정)*

대상(5): `service/RefreshTokenService` `service/LoginAttemptService` `service/EmailVerificationService`
`service/PasswordResetService` `mail/SmtpEmailSender`

검토 예정 항목:
- **fail-open / fail-closed 정책 보존**
  - fail-closed: `RefreshTokenService.saveOrReplace` `validateAndGetMemberId` — **try/catch 를 두지
    않는 것 자체가 정책**이다
  - fail-open: `RefreshTokenService.deleteByMemberId` — `catch (DataAccessException)` 후 로그만(로그아웃 멱등)
  - fail-open: `PasswordResetService.requestReset` — 미가입·탈퇴·소셜전용은 조용히 통과(계정 존재 노출 방지)
  - 예외 **타입**(`DataAccessException` `MailException`)까지 그대로 옮긴다
- **boxed `Long` JVM 호환** — `deleteByMemberId(Long)` 을 non-null `Long` 으로 조이면 JVM 에서
  primitive `long` 이 되어 아직 Java 인 `MemberService` 호출부에서 **자동 언박싱 NPE** 위험이 생기고
  `MemberServiceTest` 의 `anyLong()` 매처 계약도 흔들린다
- TTL·시도 횟수·쿨다운 상수 — `MAX_ATTEMPTS=5` `LOCK_WINDOW=10분` `COOLDOWN_SECONDS=60`
  `CODE_TTL_MINUTES=5` `TOKEN_TTL_MINUTES=30` 값·의미 동일
- **`MemberService` 호출부 무수정** — 이 PR 의 diff 에 `member/` 파일이 한 줄도 없어야 한다(완료 조건)
- 메일 예외 매핑 — `MailException` → `EMAIL_SEND_FAILED`
- 메일 본문 문자열·제목("[마켓온] …") 과 재설정 링크 형식 불변

## 6단계 — Core Service `feature/auth_kotlin_core_service` *(예정)*

대상(2): `service/OAuthSignupTransaction` `service/AuthService`

검토 예정 항목:
- **`@Transactional` 과 `Propagation.NOT_SUPPORTED`** — 클래스 `@Transactional(readOnly = true)`,
  `signup`·`login`·`reissue`·`logout` 은 `@Transactional`,
  **`startAuthorization`·`oauthLogin` 은 `@Transactional(propagation = NOT_SUPPORTED)`**.
  Kotlin 클래스는 기본 `final` 이라 allOpen 이 없으면 **`@Transactional` 이 런타임에 조용히 안 걸린다**
  — 플러그인은 이미 있으나 실제 프록시 적용을 테스트로 확인한다
- **별도 빈을 이용한 트랜잭션 경계** — `OAuthSignupTransaction` 을 별도 빈으로 유지한다.
  같은 빈 안의 self-invocation 이면 프록시가 가로채지 못해 경계가 사라진다
- **동시 소셜 가입** — `OAuthSignupTransactionConcurrencyTest`(@Tag integration) 가 고정하는 동작
- **충돌 후 reconcile** — `signUp()` → `DataIntegrityViolationException`(트랜잭션 전체 롤백) →
  별도 read-only 트랜잭션 `reconcileAfterConflict()` → empty 면 `OAUTH_EMAIL_CONFLICT`.
  **DB 벤더 예외 메시지에 의존하지 않는다**
- **`OAuthClient` 시그니처** — `AuthService` 가 `OAuthClient` 를 **파라미터로 받는다**(4단계에서 확정된 형태)
- **공개 JVM 메서드** — `signup` `login` `startAuthorization` `oauthLogin` `reissue` `logout` 6개.
  7단계까지 controller 가 Java 이므로 이름·시그니처가 하나라도 바뀌면 컴파일이 깨진다
- **`JwtTokenProvider` 호출 계약** — 이미 Kotlin 이고 `createAccessToken(Long?, String)`
  `createRefreshToken(Long?)` `getMemberId(String): Long`. **global 은 팀장 영역이라 수정 금지**

## 7단계 — Controller `feature/auth_kotlin_controller` *(예정)*

대상(3): `controller/AuthController` `controller/EmailVerificationController` `controller/PasswordResetController`
→ 이 단계로 **auth main Java 0건**

검토 예정 항목:
- Kotlin controller 파라미터 nullability / **`@CookieValue(name = "refreshToken", required = false)`**
  는 Kotlin 에서 `String?` 로 받아야 원본의 `INVALID_REFRESH_TOKEN` 분기가 유지된다
- **`@AuthenticationPrincipal Long` 바인딩** — principal 은 `JwtTokenProvider.getAuthentication` 이 넣는 `memberId`
- **HttpOnly 쿠키 계약** — `refreshToken`(Path `/`, SameSite `Lax`, Domain 미지정,
  `autoLogin=false` 면 **Max-Age 미지정 세션 쿠키**, 로그아웃은 `Duration.ZERO`) /
  `oauth_bcid`(Path `/api/auth/oauth`, 이미 있으면 **재사용**, 로그인 완료 후에도 삭제하지 않음)
- API endpoint 12개 · 응답 메시지 문자열 · HTTP 상태(201/200/400/404/409/429) 와 ErrorCode 매핑
- Jackson request binding / Bean Validation / 응답 JSON 계약
- `OAuthEndpointRateLimitOrderTest` 가 고정하는 **필터 ↔ 컨트롤러 실행 순서**
- 실제 카카오·구글 E2E → 아래 「표현 보정」절의 조건부 규칙을 따른다
- 완료 확인: `git ls-files backend/src/main/java/com/dongnemarket/auth/ | wc -l` == 0

---

# 표현 보정 *(계획 단계에서 바로잡은 것)*

계획서 초안에 부정확하거나 검증을 과대평가한 표현이 있어 실행 전에 바로잡는다.

## `Member` 플랫폼 타입 — "그대로 선언한다" 는 성립하지 않는다

초안에는 `MemberSocialAccount.member` 를 *"플랫폼 타입 그대로 선언한다"* 는 취지의 표현이 있었으나
**정확하지 않다.** 플랫폼 타입(`Member!`)은 Java 에서 값이 **넘어올 때** 컴파일러가 부여하는 타입이고,
**Kotlin 소스에 `Member!` 라고 적을 수는 없다.** 프로퍼티를 선언하는 쪽은 `Member` 든 `Member?` 든
**둘 중 하나를 반드시 고르는 결정**이며, "결정을 미룬다" 는 선택지는 없다.

따라서 2단계에서 다음 절차로 **실측해 결정한다**(현재 미확정).

1. **기준 수집** — 기존 Java 필드의 null 허용 동작(`@JoinColumn(nullable = false)` 이지만
   `protected` 무인자 생성자로 만들어진 직후에는 null)과 **JVM getter 시그니처**(`public Member getMember()`)
2. **후보 비교** — `lateinit var member: Member` ↔ nullable 프로퍼티(`var member: Member? = null`)
   ↔ 주 생성자 non-null 프로퍼티. 각각의 JVM 표면과 초기화 시점 동작이 다르다
3. **검증** — ① JPA no-arg 생성(noarg 플러그인)으로 만들어진 미초기화 상태를 견디는가
   ② `@ManyToOne(fetch = LAZY)` **프록시가 정상 생성**되는가
   ③ 아직 Java 인 `AuthService` 의 `MemberSocialAccount::getMember` 메서드 레퍼런스가 그대로 컴파일되는가
4. **확정 근거** — `javap` 시그니처 · JPA 기동(`ddl-auto=validate`) · 기존 Java 테스트 결과

**아직 구현 전이므로 특정 선언 형태를 확정된 답처럼 적지 않는다.** 2단계 수행 후 실측 결과로 이 절을 대체한다.
(`Member` 자체는 `member` 도메인 소유라 이 마이그레이션에서 수정 대상이 아니다.)

## ktlint — task 성공은 스타일 검증이 아니다

`backend/build.gradle` 의 ktlint 는 마이그레이션 기간 동안 **`ignoreFailures = true`** 다.
위반이 있어도 task 는 성공한다 — **`ktlintMainSourceSetCheck` 가 BUILD SUCCESSFUL 인 것만으로는
스타일이 검증됐다고 말할 수 없다.**

각 단계에서 다음을 함께 수행한다.

1. ktlint task 실행
2. **신규 Kotlin 파일의 위반 목록을 리포트 본문에서 직접 확인**(task 종료 코드가 아니라 출력)
3. **이번 PR 에서 추가된 신규 위반 0건**을 완료 조건으로 삼는다
4. 이미 존재하던 위반은 **별도 문제로 구분**해 기록만 남기고 이 PR 에서 고치지 않는다
   — 언어 변환 PR 에 무관한 diff 를 섞지 않는다

## 실제 카카오·구글 E2E — 조건부 필수

7단계의 "실제 소셜 로그인 왕복" 은 **환경이 갖춰졌을 때만 수행 가능한 수동 검증**이다.
필요 조건: 제공자 콘솔의 유효한 client id/secret, **등록된 redirect URI**, 그 URI 로 접근 가능한 실행 환경.

| 상황 | 요구 사항 |
|---|---|
| 자격증명·redirect URI·실행 환경이 준비됨 | 카카오·구글 각각 **실제 왕복 1회 이상 필수**. 결과(성공 여부, 발생 ErrorCode)를 PR 본문에 기록 |
| 환경이 제공되지 않음 | MockWebServer 클라이언트 테스트 · Controller 슬라이스 테스트 · **callback 계약 테스트**(state 소비·`oauth_bcid` 쿠키·오류 매핑)까지 **필수** |

**환경 부재와 기능 실패를 반드시 구분해 기록한다.** PR 본문에 "미실행(환경 미제공)" 과
"실행했으나 실패" 를 **다른 항목**으로 적는다 — 뭉뚱그리면 다음 사람이 검증됐다고 오해한다.

---

# 공통 검증 게이트 *(1~7단계 전부)*

CI 가 없으므로(위 「환경」) 아래를 **로컬에서 직접 실행하고 결과를 PR 본문에 기록**하는 것이 유일한 게이트다.

```bash
cd backend
./gradlew compileKotlin
./gradlew compileJava          # ← 전환으로 깨진 Java 호출부는 여기서만 드러난다 (grep 으로 찾지 않는다)
./gradlew compileTestKotlin
./gradlew compileTestJava      # ← 기존 Java 테스트 = 회귀 안전망. 여기가 깨지면 계약이 깨진 것
./gradlew test
./gradlew integrationTest      # Testcontainers Redis·MySQL (Docker 필요)
./gradlew ktlintMainSourceSetCheck   # ⚠️ 위 「ktlint」절 — 종료 코드만으로 판단하지 않는다
./gradlew bootJar
./gradlew clean build
```

여기에 각 단계의 `javap` 비교(위 「검증 방법론」)와 단계별 검토 항목을 더한다.
`develop` 병합 **직후** 전체 테스트를 한 번 더 돌린다 — Java↔Kotlin 혼재 기간의 도메인 간 컴파일
영향은 자기 브랜치 테스트로 잡히지 않는다(`infra/infra.md`).

---

# OpenAPI schema 계약 — `@get:JvmName` 이 문서를 깨뜨린 사례 🔴

## 발견 경위

전환 PR 을 올리기 전 **실제로 서버를 띄워 `/v3/api-docs` 를 받아** 전환 전(`origin/develop`, `cebb0a5`,
전부 Java)을 별도 포트에 띄운 결과와 대조하다가 발견했다. **기존 테스트 728건은 전부 통과한 상태였다.**

## 무엇이 달라졌나

| schema | 전환 전(Java) | 전환 후(Kotlin, 수정 전) |
|---|---|---|
| `LoginRequest.properties` | `email, password, autoLogin` | `email, password, autoLogin(writeOnly), **isAutoLogin**` |
| `LoginRequest.required` | (없음) | `isAutoLogin` |
| `SignupRequest.properties` | `…, termsAgreed, personalInfoCollectionAgreed` | + `isTermsAgreed`, `isPersonalInfoCollectionAgreed` |
| `SignupRequest.required` | (없음) | `isTermsAgreed`, `isPersonalInfoCollectionAgreed` |
| `EmailVerificationConfirmResponse` | `email, verified` | `email, verified` (동일 — 이미 `@get:JsonProperty` 가 붙어 있었다) |
| `OAuthAuthorizationStart.required` | (없음) | `expiresInSeconds` |

## 원인 두 가지

**① `@get:JvmName` 만 붙은 프로퍼티 → 팬텀 필드**
springdoc 이 `isAutoLogin()` getter 를 프로퍼티 `autoLogin` 과 **별개의 프로퍼티**로 읽는다.
그래서 읽기 전용 `isAutoLogin` 이 새로 생기고, 필드 쪽만 남은 `autoLogin` 은 `writeOnly` 로 뒤집힌다.
Jackson 은 두 접근자를 하나로 합치므로 **런타임 JSON 은 멀쩡하다** — 그래서 테스트가 통과했다.

응답 DTO(`EmailVerificationConfirmResponse`)만 멀쩡했던 이유는, JSON 필드 소실 회귀를 고치면서
이미 `@get:JsonProperty("verified")` 를 붙여뒀기 때문이다. 즉 **규칙 자체는 이미 문서에 있었는데
요청 DTO 3곳에 적용되지 않은 상태**였다.

**② Kotlin non-null 타입 → 자동 `required` 승격**
springdoc 은 Kotlin 의 non-null 프로퍼티를 `required` 로 올린다. 원본 Java 의 primitive
`boolean`/`long` 은 required 가 아니었으므로 문서 계약이 바뀐다. `OAuthAuthorizationStart.expiresInSeconds`
는 `@get:JvmName` 과 무관하게 이 경로로만 어긋났다 — 그래서 팬텀 필드만 고쳐서는 부족했다.

## 영향

프론트·모바일이 Swagger 를 보고 `isAutoLogin` 을 보내면 **서버는 조용히 무시**한다.
`required` 표기는 실제로는 선택 필드인 값을 필수로 광고한다. 순수 언어 전환 PR 이
"JSON 계약을 보존했다"고 주장하려면 **문서 계약도 같이 봐야 한다**는 것이 이 사례의 교훈이다.

## 어떻게 고쳤나 (최소 수정)

- 요청 DTO 3개 프로퍼티에 `@get:JsonProperty` 추가 — 이미 문서화돼 있던 규칙을 일관 적용한 것뿐이다.
- 자동 required 승격이 일어난 4개 프로퍼티에 `@get:Schema(requiredMode = NOT_REQUIRED)` 추가.
- **런타임 동작·JVM 시그니처·검증 어노테이션은 건드리지 않았다.** 호출부·service·controller 변경 0줄.

### 선택하지 않은 대안

| 대안 | 버린 이유 |
|---|---|
| 프로퍼티를 nullable(`Boolean?`)로 바꿔 required 회피 | JVM 시그니처가 primitive `boolean` → `Boolean` 으로 바뀐다. 언어 전환 PR 이 지켜야 할 것을 정면으로 깬다 |
| `@get:Schema(hidden = true)` 로 팬텀 getter 숨기기 | 정상 프로퍼티까지 함께 사라진다(같은 프로퍼티로 합쳐지기 때문) |
| springdoc 전역 설정으로 Kotlin nullability 추론 끄기 | `application.yml` 은 공용 설정 — 팀장 영역이고 전 도메인에 영향이 간다 |
| `@field:Schema` / `@param:Schema` | getter 쪽에서 생기는 문제라 자리가 맞지 않는다 |

## 검증 방법

`AuthOpenApiContractTest` — MockMvc 로 `/v3/api-docs` 를 실제로 받아 auth schema 11개의
프로퍼티 이름 집합·`required`·`readOnly`/`writeOnly` 를 전환 전 실측값과 대조한다(16건).
전체 JSON snapshot 은 쓰지 않는다 — 무관한 정렬·추가로 깨지기 때문이다. develop 서버가 떠 있을 필요도 없다.

**음성 대조를 반드시 했다** — `@get:JsonProperty`/`@get:Schema` 를 다시 떼고 돌리면 16건 중 8건이
`LoginRequest: [isAutoLogin]`, `autoLogin` writeOnly 등 정확한 진단과 함께 실패한다.
어노테이션을 붙였다는 사실이 아니라 **생성된 문서**가 판단 기준이다.

---

# PR B — 영속성 entity·JPA repository (2단계)

대상 7개: entity 3(`EmailVerification`·`MemberSocialAccount`·`RefreshToken`)
+ JPA repository 4(`EmailVerificationRepository`·`MemberSocialAccountRepository`
·`RefreshTokenJpaEntityRepository`·`JpaRefreshTokenRepository`).

기준선은 PR #76 이 병합된 `origin/develop`(`9c5ec8f`). 1단계와 같은 방식으로 변환 전 `javap -p` 를 저장해 두고
변환 후 대조했다.

## 영속성 entity — 세 가지 제약이 동시에 걸린다

| 제약 | 왜 | 어기면 |
|---|---|---|
| 클래스·getter 가 `final` 이면 안 된다 | Hibernate 지연 로딩 프록시가 엔티티를 **상속**해 만들어진다 | `MemberSocialAccount.member`(LAZY) 초기화 실패 |
| 참조형 필드를 non-null 로 조이면 안 된다 | `Long` → primitive `long` 으로 바뀐다 | 아직 Java 인 호출부에서 자동 언박싱 NPE |
| 컬럼·테이블·제약 이름이 바뀌면 안 된다 | Flyway 스키마와 `ddl-auto=validate` 가 그대로다 | 기동 시점 스키마 불일치 |

setter 는 `protected` 로 뒀다. `build.gradle` 의 allOpen 이 프로퍼티까지 open 으로 만들어 Kotlin 이
open 프로퍼티의 private setter 를 금지하기 때문이며, `BaseTimeEntity` 가 이미 같은 이유로 같은 선택을 해뒀다.
원본에 없던 `protected setXxx()` 가 생기지만 **public 표면은 넓어지지 않는다**(테스트로 고정).

## `EmailVerification.verified` — 이름 두 개가 동시에 필요했던 자리 🔴

한 프로퍼티에 서로 다른 두 계약이 걸려 있었다.

| 무엇이 | 어떤 이름을 요구하나 | 근거 |
|---|---|---|
| Spring Data 파생 쿼리 | 속성명 **`verified`** | `EmailVerificationRepository.existsByEmailAndVerifiedTrue` 가 속성명으로 해석된다 |
| Java 호출부 | getter **`isVerified()`** | `EmailVerificationServiceTest` 가 그대로 호출한다 |

Kotlin 은 `val verified` 의 getter 를 `getVerified()` 로 만든다. 그래서 `@get:JvmName("isVerified")` 를 붙였고,
Kotlin 이 `@JvmName` 을 open 멤버에 금지하므로 **이 프로퍼티만 `final`** 이다.

*버린 대안* — 프로퍼티 이름을 `isVerified` 로 바꾸기. getter 는 자연스럽게 `isVerified()` 가 되지만
**파생 쿼리가 속성 `verified` 를 찾지 못해 애플리케이션 기동이 깨진다.** 컬럼 이름도 `is_verified` 로 바뀐다.
이름을 바꾸는 우회는 언제나 다른 계약을 건드린다는 점이 1단계 `@get:JvmName` 사례와 같다.

이 프로퍼티의 finality 가 안전한 이유는 **`EmailVerification` 을 지연 로딩 프록시로 받는 연관관계가 없기** 때문이다.
`MemberSocialAccount.member` 처럼 프록시 대상인 자리에는 `@JvmName` 을 쓰지 않았다.

## 실제로 잡은 회귀 — `Long` 이 primitive 로 바뀜 🔴

`RefreshTokenJpaEntityRepository` 를 처음엔 파라미터를 non-null `Long` 으로 옮겼다.
**컴파일도 통과하고 기존 테스트도 전부 통과했다.** 그런데 `javap` 비교에서 드러났다.

```
- public abstract Optional<RefreshToken> findByMemberId(java.lang.Long);
+ public abstract Optional<RefreshToken> findByMemberId(long);
```

descriptor 가 `(Ljava/lang/Long;)` → `(J)` 로 바뀌었다. 아직 Java 인 호출부가 `null` 을 넘기면
자동 언박싱 NPE 가 난다. `Long?` 로 되돌려 원본과 완전히 일치시켰다.
**1단계 `oidcNonce` 와 정확히 같은 계열의 함정**이고, 역시 테스트가 아니라 시그니처 비교로만 잡혔다.

## repository — 바꾸지 않은 것

`Optional` 반환, `List` 반환, JPQL 문자열, `@Param` 이름, 상속 구조, 제네릭 타입을 전부 그대로 뒀다.
`@Param` 은 `@Target` 에 PARAMETER 만 있어 use-site target 없이도 파라미터에 붙는다.
`JpaRefreshTokenRepository` 가 구현하는 `RefreshTokenRepository` 는 아직 Java 인터페이스(3단계 대상)라,
파라미터를 원본 참조형에 맞춰 nullable 로 선언했다.

## 검증 방법

계약별로 테스트 파일을 나눴다 — 실패했을 때 원인이 바로 보이게 하기 위해서다.

| 파일 | 무엇을 고정하나 |
|---|---|
| `AuthPersistenceJvmSurfaceTest` | JVM 시그니처·생성자 가시성·finality·JPA 어노테이션 값(리플렉션) |
| `AuthPersistenceMappingTest` | 실제 Hibernate 컨텍스트 부팅·저장/조회·파생 쿼리·enum 저장·LAZY 프록시 |

`AuthPersistenceMappingTest` 는 `@DataJpaTest` 슬라이스라 `JpaAuditingConfig` 가 자동으로 포함되지 않는다.
`@Import` 로 명시해야 `BaseTimeEntity.createdAt` 이 채워진다(전환 문제가 아니라 슬라이스 범위 문제).

## 남은 허용 차이

| 분류 | 항목 | 왜 남는가 |
|---|---|---|
| Kotlin 구조 필수 | `protected setXxx()` 3~4개/엔티티 | allOpen + open 프로퍼티 제약. public 표면은 불변 |
| Kotlin 필수 synthetic | `DefaultConstructorMarker` 생성자 | `ACC_SYNTHETIC` 확인 — Java 소스에서 호출 불가 |
| 언어 제약 | `@JvmStatic` 팩토리가 `public static final` | Kotlin 이 항상 `ACC_FINAL` 을 붙인다. static 은 오버라이드 대상이 아니라 의미상 차이 없음 |
| 언어 제약 | `EmailVerification.isVerified()` 가 `final` | `@JvmName` 과 `open` 병용 금지. 프록시 대상 아님을 확인함 |
| Kotlin 구조 | `Companion` 필드 | additive |

---

# PR C — 저장소 구현 (3단계)

대상 14개: 추상화 5(`EmailVerificationCodeRepository`·`LoginAttemptRepository`·`OAuthStateRepository`
·`PasswordResetTokenRepository`·`RefreshTokenRepository`) + Redis 구현 5 + InMemory 구현 4.
이로써 **`auth/repository` 패키지의 Java 파일이 0개**가 됐다. 기준선은 PR #78 병합본(`6d3cdac`).

## 이 계층에서 조용히 깨지는 것

| 무엇이 | 어떻게 | 대응 |
|---|---|---|
| 참조형 파라미터 | non-null 로 조이면 `Long` → primitive `long` | 원본 참조형은 전부 nullable 유지 |
| `Optional` 반환 | Kotlin nullable 로 바꾸면 Java 호출부의 `.orElseThrow()` 가 깨짐 | `Optional` 그대로 |
| `@Profile` | 빠지면 test 에서 Redis 를, 운영에서 InMemory 를 잡는다 | 어노테이션 값까지 테스트로 고정 |
| Redis key·TTL·Lua | 한 글자만 달라도 저장 형식이 바뀐다 | **문자열을 그대로 복사**하고 기존 Testcontainers 테스트로 확인 |

## Redis 구현 — 바꾸지 않은 저장 계약

key prefix 5종(`auth:email:verify:` · `auth:login:fail:` · `auth:refresh:` ·
`auth:password:reset:member:` · `auth:password:reset:token:`)과 OAuth state 의
Hash/ZSET key(`auth:oauth:state:{state}` · `auth:oauth:bcid:{bch}:states`), Lua 스크립트 두 개의
본문·ARGV 순서·Hash 필드 이름을 **그대로 옮겼다.**

특히 유지해야 했던 순서 의존 두 가지:
- `INCR` 결과가 **정확히 1일 때만** `EXPIRE` — 매번 걸면 윈도우가 연장돼 차단이 풀리지 않는다.
- 비밀번호 재설정은 member 키 먼저, token 키 나중에 **같은 TTL** 로 저장.

상수는 `private const val` 로 companion object 에 뒀다. 원본이 `private static final` 이라
외부 접근 경로가 없어 Java 표면에 영향이 없다(반대로 **public 상수였다면** `const val`/`@JvmField` 로
접근 경로를 맞춰야 했다).

## InMemory 구현 — 유지한 동시성·만료 계약

`ConcurrentHashMap` + 메서드 단위 `synchronized` 를 그대로 뒀다(Kotlin 은 `@Synchronized` 로
같은 `ACC_SYNCHRONIZED` 를 만든다). 조회 시점 lazy 만료, 최초 실패에만 윈도우를 잡는 정책,
`InMemoryPasswordResetTokenRepository.clear()` 의 **public 가시성**(테스트 격리용 고유 API)도 유지했다.

`InMemoryOAuthStateRepository.consume` 의 **"만료면 삭제 후 empty / 불일치면 삭제하지 않고 empty"** 구분은
보안 계약이다 — 불일치일 때 삭제해 버리면 공격자가 정상 state 를 소모시킬 수 있다. 테스트로 고정했다.

## 전환 중 실제로 부딪힌 것

| # | 증상 | 원인 | 처리 |
|---|---|---|---|
| 1 | `browserCorrelationHash()` 등이 "cannot be invoked as a function" | `OAuthAuthorizationState` 는 1단계에서 Kotlin `@JvmRecord data class` 가 됐다. **Java 에서는 `x()` , Kotlin 에서는 `x`** 로 접근한다 | Kotlin 접근자 문법으로 수정. JVM 표면은 그대로 |
| 2 | `value.provider.name` 타입 불일치 | record 컴포넌트가 원본 Java 와 동일하게 nullable | 원본이 NPE 였던 경로라 `!!` 로 같은 의미 유지 |

두 건 모두 **1단계 결과 위에서만 나타나는 상호작용**이라, 단계별 전환에서 앞 단계 산출물의
Kotlin 접근 방식을 확인해야 한다는 사례로 남긴다.

## JVM 표면 비교 결과

14개 중 **9개는 public 표면 완전 일치**, 나머지 5개(Redis 구현)는 `Companion` 필드 하나만 추가됐다.
**메서드 시그니처는 14개 전부 동일**하며, Java 호출부(service 5개)를 한 줄도 고치지 않고 `compileJava` 가 통과한다.

## 검증

| 항목 | baseline(`6d3cdac`) | 3단계 후 |
|---|---|---|
| `test` | 781 / 실패 0 / skip 0 | **818 / 실패 0 / skip 0** (+37) |
| auth | 235 | **272** (+37) |
| `integrationTest` | 44 실행 / 43 통과 / 비활성화 1 | **동일** |

신규 테스트는 `AuthStoreJvmSurfaceTest`(13건 — 시그니처·프로파일·빈 경계)와
`AuthStoreContractTest`(24건 — TTL·덮어쓰기·consume·발급 한도)로 나눴다.
Redis key·TTL·Lua 원자성은 기존 Testcontainers 통합 테스트가 이미 담당해 중복하지 않았다.

---

# PR D — OAuth client·config (4단계)

대상 7개: client 6(`OAuthClient`·`OAuthClientErrorMapper`·`OAuthAuthorizationUrlFactory`
·`KakaoOAuthClient`·`GoogleOAuthClient`·`GoogleIdTokenValidator`) + config 1(`OAuthWebClientConfig`).
기준선은 PR #80 병합본(`1fae662`).

## 이 계층에서 지켜야 했던 것

| 계약 | 왜 위험한가 |
|---|---|
| `@Bean` 메서드 이름 `oauthWebClient` | Kotlin 함수 이름이 곧 빈 이름이고, 카카오·구글 클라이언트가 **파라미터 이름으로** 이 빈을 지목한다 |
| form/query 파라미터 이름·값 | 제공자와의 계약. `scope`(`account_email` / `openid email`)·`code_challenge_method=S256` 등 |
| 응답 JSON 필드명 | `@JsonNaming(SnakeCaseStrategy)` + `@JsonIgnoreProperties(ignoreUnknown = true)` 유지 |
| 오류 매핑 순서 | `WebClientResponseException`(4xx/그 외) → `WebClientRequestException` → `CodecException`/`DataBufferLimitException` → `RuntimeException(cause=Timeout)` |
| nonce 상수시간 비교 | `MessageDigest.isEqual` 을 `==` 로 바꾸면 타이밍 공격 방어가 사라진다 |

## 응답 DTO 를 non-null 로 조이지 않은 이유

원본 Java record 의 필드는 전부 참조형이고, **null 일 때 정해진 `ErrorCode` 로 실패하는 흐름 자체가 계약**이다
(`accessToken` 없음 → `OAUTH_PROVIDER_ERROR`, 이메일 없음 → `OAUTH_EMAIL_NOT_PROVIDED`,
미인증 → `OAUTH_EMAIL_NOT_VERIFIED`). Kotlin 에서 non-null 로 선언하면 Jackson 역직렬화 시점에
`MissingKotlinParameterException` 이 나면서 **오류 종류가 통째로 바뀐다.** 그래서 nullable + 기본값 `null` 로 뒀다.

## `OAuthClientErrorMapper` — 유일하게 표면이 넓어진 자리 🔴

원본은 **package-private `final class`** + private 생성자 + **package-private `static` 메서드**였다.
**Kotlin 에는 package-private 가시성이 없다.** `internal` 로 선언해도 JVM 바이트코드에서는 `public` 이 된다.

| | 전환 전 | 전환 후 |
|---|---|---|
| 클래스 | `final class`(package-private) | `public class` |
| `call` | `static`(package-private) | `public static` |
| 추가 | — | `Companion` 필드, `static {}` |

**버린 대안**
- top-level 함수로 옮기기 → `OAuthClientErrorMapperKt` 라는 새 public 클래스가 생기고 호출 경로가 바뀐다.
- `private` 로 낮추기 → 카카오·구글 두 클라이언트에서 못 쓴다.
- 각 클라이언트에 로직 복제 → 매핑 규칙이 두 곳으로 갈라진다(원본이 공통화한 이유를 깬다).

`internal` 을 붙여 **Kotlin 코드에서는 모듈 밖 접근이 컴파일 단계에서 막히도록** 했고, 이 클래스는
`auth.client` 패키지 내부 유틸이라 실사용상 노출 위험은 없다고 판단했다. 나머지 6개는 공개 표면 diff 0 이다.

## 검증

| 항목 | baseline(`1fae662`) | 4단계 후 |
|---|---|---|
| `test` | 818 / 실패 0 / skip 0 | **839 / 실패 0 / skip 0** (+21) |
| auth | 272 | **293** (+21) |
| `integrationTest` | 44 실행 / 43 통과 / 비활성화 1 | **동일** |

신규 테스트는 `OAuthClientJvmSurfaceTest`(13건 — 시그니처·빈 이름·생성자 파라미터 타입)와
`OAuthAuthorizationUrlContractTest`(8건 — 인가 URL 의 파라미터 이름·값)로 나눴다.
쿼리 파라미터 **순서는 계약으로 고정하지 않았다** — 원본이 순서를 보장한다는 근거가 없다.

**실서비스 카카오·구글 OAuth 수동 검증은 하지 않았다.** 개발용 client id/secret 과 등록된 redirect URI 가
없어 실행할 수 없는 외부 연동 절차다. 테스트에는 `test-kakao-client-id` 같은 명백한 dummy 값만 썼다.
토큰 교환·사용자정보 호출의 실제 HTTP 왕복은 기존 `KakaoOAuthClientTest`·`GoogleOAuthClientTest`(MockWebServer)가
담당하며, 이번 전환 후에도 그대로 통과한다.

---

# PR E — 지원 서비스·메일 (5단계)

대상 5개: 지원 서비스 4(`LoginAttemptService`·`RefreshTokenService`·`EmailVerificationService`
·`PasswordResetService`) + mail 구현 1(`SmtpEmailSender`). 기준선은 PR #82 병합본(`0a5af2d`).
6단계 대상(`AuthService`·`OAuthSignupTransaction`)과 controller 3개는 손대지 않았다.

## 이 계층에서 조용히 깨지는 것

| 무엇이 | 어떻게 | 대응 |
|---|---|---|
| 클래스 finality | Kotlin 기본 `final` 이면 `@Transactional` CGLIB 프록시가 안 만들어져 **트랜잭션이 조용히 사라진다** | allOpen 결과를 테스트로 확인 |
| `@Transactional` 위치 | 클래스 레벨과 메서드 레벨(readOnly)이 뒤바뀌면 읽기 전용 경계가 사라진다 | 어노테이션 값까지 테스트로 고정 |
| 반환 타입 | boxed `Long` → primitive `long` | `Long?` 유지 (아래 회귀) |
| 호출 순서 | 보안·정합성 계약인 자리가 있다 | 원본 순서 그대로 |

## 유지한 호출 순서 (보안·정합성 계약)

- `EmailVerificationService.confirmVerification` — 이미 인증됨 확인 → 코드 조회 → 문자열 일치 →
  **코드 삭제 후** 인증 상태 반영. 삭제를 뒤로 미루면 같은 코드를 두 번 쓸 여지가 생긴다.
- `PasswordResetService.requestReset` — `DELETED` 아님 → 로컬 로그인 가능만 통과, 쿨다운 중이면 **조용히 반환**
  (계정 존재 여부를 응답으로 노출하지 않는다). 재요청 시 **이전 토큰을 먼저 무효화**한 뒤 새 토큰 저장.
- `PasswordResetService.confirmReset` — 토큰 해시 조회 → 회원 조회 → 로컬 로그인 가드 → 비밀번호 변경 →
  **tokenHash 삭제 → memberId 삭제 → refresh token 삭제**.
- `RefreshTokenService.validateAndGetMemberId` — JWT 파싱 → refresh 타입 확인 → 저장소 조회 → 문자열 일치.
  catch 순서(`ExpiredJwtException` 먼저)도 그대로다 — 뒤집으면 만료 토큰이 INVALID 로 매핑된다.
- `RefreshTokenService.deleteByMemberId` — 로그아웃만 fail-open(`DataAccessException` 을 삼키고 로그만).
  나머지는 fail-closed(try/catch 를 두지 않는 것 자체가 정책).

## 실제로 잡은 회귀 — boxed `Long` → primitive `long` 🔴

`validateAndGetMemberId` 를 Kotlin `Long`(non-null)으로 옮겼더니 반환 타입이 primitive `long` 이 됐다.

```
- public java.lang.Long validateAndGetMemberId(java.lang.String);
+ public long validateAndGetMemberId(java.lang.String);
```

**컴파일도 통과하고 기존 테스트 839건도 전부 통과했다** — Java 호출부(`AuthController`)가 자동 언박싱으로
받기 때문이다. 이번에 추가한 `AuthSupportServiceJvmSurfaceTest` 가 잡아냈고 `Long?` 로 복원했다.
1단계 `oidcNonce`·2단계 `findByMemberId`·3단계와 **같은 "참조형 → primitive 축소" 계열**이다.

## 메일 계약

`SmtpEmailSender` 는 발신자 표기(`마켓온 <주소>`), `SimpleMailMessage`(HTML 아님), 필드 설정 순서
(from → to → subject → text), `MailException` → `EMAIL_SEND_FAILED` 매핑, 실패 로그에 수신자만 남기는 처리를
그대로 유지했다. `@Async` 를 새로 붙이지 않았다(원본이 동기 발송).
메일 제목 2종(`[마켓온] 이메일 인증 코드 안내`·`[마켓온] 비밀번호 재설정 안내`)과 본문 문자열,
재설정 링크 형식(`{base}/password-reset?token={raw}`)도 문자열 그대로다.

**실제 SMTP 발송은 하지 않았다.** 기존 mock 기반 테스트가 발송 호출을 검증한다.

## 두 번째 회귀 — `EmailVerificationService` 의 null 계약 🔴

초안에서 `email` 을 메서드 진입 직후 non-null 로 좁히는 가드를 넣었다. 그 결과 **실패 시점과 예외 종류가
함께 바뀌었다** — 원본은 첫 역참조 지점에서 NPE 였는데 진입 직후 `INVALID_INPUT_VALUE` 가 됐다.
정상 API 요청(`@NotBlank` 통과)에서는 도달하지 않는 경로지만, **Java 에서 이 서비스를 직접 호출하는
런타임 계약**까지 보존해야 순수 언어 전환이므로 되돌렸다.

원본(`0a5af2d`)의 null 경로를 다시 확인해 맞췄다.

| 메서드 | 실패 전 실행되는 것 | 첫 실패 지점 | 실행되지 않는 것 |
|---|---|---|---|
| `requestVerification` | `existsByEmail(null)`(읽기) | `getRemainingTtl(null)` — `ConcurrentHashMap` 이 null 키 거부 → **NPE** | 코드 저장·인증상태 무효화·메일 발송 |
| `confirmVerification` | `existsByEmailAndVerifiedTrue(null)`(읽기) | `findCode(null)` → **NPE** | 코드 삭제·인증상태 반영·메일 발송 |

복원 방식 — 파라미터·지역 변수를 nullable `String?` 로 되돌리고, **Kotlin 타입 시스템이 non-null 을
요구하는 지점에만** `!!` 를 뒀다. 임의 기본값도, `INVALID_INPUT_VALUE` 신규 매핑도 없다.
두 메서드 모두 **실패 전 쓰기 작업이 0건**이라 트랜잭션 rollback 후 남는 상태도 원본과 같다.

### 파고들어 나온 세 번째 회귀 — 2단계 repository 파라미터 nullability 누락 🔴

null 경로를 복원하는 과정에서 `confirmVerification` 이 여전히 원본과 다른 순서로 실패한다는 것을 발견했다.
`existsByEmailAndVerifiedTrue` 가 2단계 전환에서 파라미터를 non-null 로 옮겨둔 탓에 Kotlin 호출부가
`!!` 를 강제받았고, 그래서 **첫 조회를 수행하기도 전에** 실패했다.

**JVM descriptor 는 동일하다** — `(Ljava/lang/String;)Z` 그대로다. 그래서 2단계의 `javap` 비교에서
드러나지 않았고, Java 호출부도 아무 문제 없이 컴파일됐다. **Kotlin 호출부에서만 계약이 달라지는 종류**다.

2단계 repository 4개를 전수 재점검한 결과 원본 Java 참조형을 non-null 로 좁힌 파라미터가 다음과 같았다.

| 파일 | 메서드 | 원본 Java | 전환 후 | 조치 |
|---|---|---|---|---|
| `EmailVerificationRepository` | `findByEmail` | `String` | `String` non-null | `String?` 복원 |
| `EmailVerificationRepository` | `existsByEmailAndVerifiedTrue` | `String` | `String` non-null | `String?` 복원 |
| `MemberSocialAccountRepository` | `findByProviderAndProviderUserIdFetchMember` | `OAuthProvider`·`String` | 둘 다 non-null | 둘 다 nullable 복원 |
| `RefreshTokenJpaEntityRepository` | `findByMemberId`·`deleteByMemberId` | `Long` | `Long?` | 이미 정상 |
| `JpaRefreshTokenRepository` | `findByMemberId`·`deleteByMemberId` | `Long` | `Long?` | 이미 정상 |
| `RefreshTokenRepository`(3단계) | `save` | `RefreshToken` | non-null | `RefreshToken?` 복원 |
| `JpaRefreshTokenRepository` | `save` | `RefreshToken` | non-null | `RefreshToken?` 복원 |
| `RedisRefreshTokenRepository`(3단계) | `save` | `RefreshToken` | non-null | `RefreshToken?` 복원 |

복원 후 `confirmVerification(null)` 의 호출 순서가 원본과 같아졌다:
`existsByEmailAndVerifiedTrue(null)`(읽기) → `findCode(null)` → NPE.
`requestVerification(null)` 도 `existsByEmail(null)` → `getRemainingTtl(null)` → NPE 로 동일하다.
`emailSender.send` 에만 `!!` 가 남는데, 원본에서도 그 지점이 메일 계층에 null 을 넘기던 자리이고
test 프로파일에서는 그 앞에서 이미 실패해 도달하지 않는다.

`EmailVerificationNullContractTest` 가 Mockito `InOrder` 로 두 메서드의 조회 순서를 고정한다.

**교훈** — `javap` descriptor 비교만으로는 이 회귀를 잡을 수 없다. Kotlin nullability 는 descriptor 가 아니라
메타데이터에 실리기 때문이다. 원본이 Java 참조형이면 Kotlin 에서도 nullable 로 두는 것을 기본값으로 삼는다.

`validateAndGetMemberId` 의 boxed `Long` 회귀와는 **별개의 두 번째 실제 회귀**다.
둘 다 계약 테스트(`AuthSupportServiceJvmSurfaceTest`·`EmailVerificationNullContractTest`)로 고정했다.

## 검증

| 항목 | baseline(`0a5af2d`) | 5단계 후 |
|---|---|---|
| `test` | 839 / 실패 0 / skip 0 | **852 / 실패 0 / skip 0** (+13) |
| auth | 293 | **306** (+13) |
| `integrationTest` | 44 실행 / 43 통과 / 비활성화 1 | **동일** |

### `RefreshTokenRepository.save` — 전수 점검의 마지막 축소

repository 전수 점검에서 마지막으로 `save(RefreshToken)` 이 non-null 로 좁혀져 있는 것을 발견했다.
추상화 1개와 구현 2개(JPA·Redis)를 **함께** 복원해야 해서 3단계 파일까지 범위에 들어왔다.

**JVM descriptor 는 바뀌지 않는다** — 셋 다 `(Lcom/dongnemarket/auth/entity/RefreshToken;)L…RefreshToken;` 그대로다.
복원한 것은 **Kotlin 호출 계약과 메서드 진입 null 검사**뿐이다.

원본의 null 실패 지점을 그대로 맞췄다. Java 는 수신자를 먼저 평가한 뒤 인자를 평가한다.

| 구현 | 실패 전 호출 | 첫 역참조 | 외부 저장소 쓰기 |
|---|---|---|---|
| `JpaRefreshTokenRepository` | 없음(`jpaRepository` 는 필드) | `refreshToken.getMemberId()` → NPE | `findByMemberId`·`save` **미호출** |
| `RedisRefreshTokenRepository` | `opsForValue()` 1회(수신자 평가) | `key(refreshToken.getMemberId())` → NPE | `set` **미호출** |

non-null 로 조여 두면 메서드 진입 null 검사가 먼저 걸려 **Redis 쪽의 `opsForValue()` 호출조차 사라진다.**
그래서 nullable 파라미터 + 첫 역참조 위치 `!!` 조합으로 맞췄다. **두 구현 모두 null 입력 시 외부 저장소에
쓰기 전에 실패한다.**

`AuthRepositoryNullabilityContractTest`(추상화·구현체 nullability + `!!` 없는 호출 fixture + descriptor·오버로드)와
`RefreshTokenSaveNullContractTest`(null 입력 시 interaction, 정상 저장 경로)가 이를 고정한다.

이 수정은 5단계 기능 확장이 아니라 **2·3단계에서 발견되지 않았던 Kotlin nullability 호환성 수정**이다.

---

# PR F — 핵심 인증 서비스 (6단계)

대상 2개: `OAuthSignupTransaction`(트랜잭션 경계 컴포넌트) + `AuthService`(orchestrator).
기준선은 PR #84 병합본(`556c3ac`). controller 3개(7단계)는 손대지 않았다.

## 이 계층에서 조용히 깨지는 것

| 무엇이 | 어떻게 | 대응 |
|---|---|---|
| 트랜잭션 propagation | `oauthLogin` 이 클래스 readOnly 를 상속받으면 제공자 네트워크 호출이 DB 커넥션을 붙든 채 일어난다 | `NOT_SUPPORTED` 를 어노테이션 값까지 테스트로 고정 |
| 별도 빈 분리 | `signUp` 과 `reconcileAfterConflict` 를 같은 빈으로 합치면 self-invocation 으로 프록시가 못 가로채 **두 트랜잭션이 분리되지 않는다** | 별도 클래스·생성자 주입을 테스트로 고정 |
| 협력자 호출 순서 | state 소비가 제공자 호출보다 뒤로 가면 실패한 시도의 state 가 재사용 가능해진다. 차단 확인이 회원 조회보다 뒤로 가면 차단 상태에서 자격증명 확인이 일어난다 | `inOrder` 테스트로 고정 |
| null 실패 위치 | 파라미터를 non-null 로 조이면 진입 검사가 삽입돼 원본 첫 역참조 지점보다 실패가 앞당겨진다 | nullable + 원본 역참조 지점 `!!` |

## 유지한 트랜잭션 계약

| 메서드 | 계약 |
|---|---|
| `AuthService` 클래스 | `@Transactional(readOnly = true)` |
| `signup`·`login`·`reissue`·`logout` | 메서드 `@Transactional` (REQUIRED 쓰기) |
| `startAuthorization`·`oauthLogin` | `Propagation.NOT_SUPPORTED` |
| `OAuthSignupTransaction.signUp` | `@Transactional` (가입 원자성, 실패 시 전체 롤백) |
| `OAuthSignupTransaction.reconcileAfterConflict` | `@Transactional(readOnly = true)` (롤백 뒤 **새** 트랜잭션 재조회) |

`javap -v` 로 6개 메서드 전부 원본과 동일한 위치·값임을 확인했고,
`AuthCoreServiceTransactionContractTest` 가 고정한다.

## null 실패 위치 — 원본에서 역참조 앞에 부수효과가 있는 자리 🔴

원본 Java 는 null 인자를 **처음 역참조하는 지점**에서 NPE 로 실패하고, 그 앞의 부수효과는 이미 실행된
상태였다. 이 위치가 특히 눈에 띄는 두 자리:

| 메서드 | null 이전에 실행되는 것 | 원본 실패 지점 |
|---|---|---|
| `startAuthorization(null, …)` | state·code_verifier 난수 2회 + SHA-256 해시 | `client.provider()` |
| `signUp(null)` | 더미 비밀번호 `encode()` 1회 | `identity.provider()` |
| `generateNickname(null)` (private) | `StringBuilder` 생성 + **난수 10회 소비** | `provider.name()` |

`generateNickname` 은 컴파일 에러로 드러났다 — `OAuthUserIdentity.provider` 가 nullable 인데 파라미터를
non-null 로 두면 호출부에 `!!` 가 강제되어 실패가 진입 시점으로 앞당겨지고 `secureRandom` 소비량까지
달라진다. 파라미터를 nullable 로 두고 원본 역참조 지점(`provider!!.name`)에 `!!` 를 뒀다.
`AuthCoreServiceNullabilityContractTest` 가 리플렉션 + 컴파일 fixture + interaction 검증으로 고정한다.

## Java 관용구와 다른 Kotlin 기본값 두 개

| 원본 Java | 그대로 옮기면 | 실제 전환 |
|---|---|---|
| `String.toLowerCase()` (기본 로케일) | Kotlin `lowercase()` 는 `Locale.ROOT` 고정 | `lowercase(Locale.getDefault())` |
| `String.getBytes()` (플랫폼 기본 charset) | Kotlin `toByteArray()` 는 UTF-8 고정 | `toByteArray(Charset.defaultCharset())` |

둘 다 현재 값 범위(provider enum 이름, base64url ASCII)에서는 결과가 같지만, 의미를 바꾸지 않고 옮겼다.

## 허용한 차이 — `reissue` 의 `findById` 전달 🔴

`memberRepository.findById` 는 Spring Data `@NonNullApi` 라 Kotlin 에서 non-null `Long` 을 요구한다.
원본 Java 는 null 을 그대로 전달했고 `findById` 내부 `Assert.notNull` 이 `IllegalArgumentException` 을
던졌다. Kotlin 은 null 그대로 전달하는 형태가 불가능해 `requireNotNull(memberId)` 로 옮겼다 —
같은 `IllegalArgumentException` 계열, 같은 문장 위치이며 메시지는 다르다(메시지 의존 코드는 금지라 무해).
실제로는 `validateAndGetMemberId` 가 모든 실패 경로에서 `BusinessException` 을 던지고 non-null 을
반환하므로 **이 지점은 도달 불가다.**

`oauthLogin` 의 member 는 `MemberSocialAccount.member`(Kotlin 엔티티의 `Member?`)가 흘러들어오므로
private 헬퍼(`issueTokens`·`validateActiveStatus`)도 nullable 파라미터 + 원본 역참조 지점 `!!` 로 맞췄다.

## 테스트 작성 관행 — Mockito 매처와 Kotlin non-null 파라미터

Mockito 매처(`any`·`eq`·`captor.capture()`)는 null 을 반환하는데, Kotlin **non-null 파라미터** 자리에
넣으면 매처가 등록되기도 전에 호출부 intrinsic null 검사("must not be null")가 터진다.
`ArgumentCaptor.forClass` 반환이 platform 제네릭이라 헬퍼의 타입 추론까지 platform 으로 오염되는 것도
같은 계열이다. 매처를 등록한 뒤 타입만 맞춘 값을 돌려주는 헬퍼(`anyObj`/`eqObj`/`cap`,
mockito-kotlin 과 같은 방식) + captor 변수의 명시적 Kotlin 타입 선언으로 우회했다.

## 검증

- `javap -public` 원본 대조: 두 클래스 모두 public 표면 동일 (유일한 추가는 `private const` 로 인한
  `Companion` static 필드 — 5단계 서비스들과 동일 패턴)
- 생성자 13개 파라미터 순서, `@Value` 2개 primitive `long`/`int` 유지
- 신규 계약 테스트 46건: JVM 표면 13 + 트랜잭션 8 + 호출 순서 15 + nullability 10
- 전체 `test` 923(기존 877 + 신규 46) / 실패·오류 0 / skip 0
- `integrationTest` 44 / 실패·오류 0 / 비활성 1(기존 `OAuthEndpointRateLimitOrderTest`)
- `OAuthSignupTransactionConcurrencyTest` 2/2 통과 — 동시 가입 레이스에서 reconcile 경로 실동작 확인
- `bootJar` 성공, strict ktlint(1.5.0) 위반 0

---

# PR G — 컨트롤러 (7단계, 마지막)

대상 3개: `AuthController`(8 endpoint) + `EmailVerificationController`(2) + `PasswordResetController`(2).
기준선은 PR #86 병합본(`6515742`). **이 브랜치 기준 auth main Java 0개** — PR 병합 후 develop 기준
전환 완료 예정.

## API 12개 (전후 method·path 차이 0)

| Method | Path | Handler |
|---|---|---|
| POST | `/api/auth/signup` | signup |
| POST | `/api/auth/login` | login |
| POST | `/api/auth/reissue` | reissue |
| POST | `/api/auth/logout` | logout |
| POST | `/api/auth/oauth/kakao/authorization` | kakaoAuthorization |
| POST | `/api/auth/oauth/google/authorization` | googleAuthorization |
| POST | `/api/auth/oauth/kakao/login` | kakaoLogin |
| POST | `/api/auth/oauth/google/login` | googleLogin |
| POST | `/api/auth/email-verifications` | requestVerification |
| POST | `/api/auth/email-verifications/confirm` | confirmVerification |
| POST | `/api/auth/password-resets` | requestReset |
| POST | `/api/auth/password-resets/confirm` | confirmReset |

## 전후 비교 방법 — snapshot 이 계약이다

전환 **전** Java 상태에서 `RequestMappingHandlerMapping` 의 (method, path, handler, 파라미터
annotation, 제네릭 반환 타입) 과 `/v3/api-docs` 전문을 임시 파일로 떠 두고, 전환 후 같은 방식으로
다시 떠 diff 했다 — **mapping 12개 동일, OpenAPI 문서 바이트 단위 동일.**
`javap -public` 도 3개 클래스 모두 동일(추가는 `private const` 로 인한 `Companion` static 필드뿐,
`EmailVerificationController` 는 상수가 없어 그것도 없음).

## endpoint 파라미터 nullability — Spring binding 의미가 기준 🔴

이 계층에서는 "Java 참조형 → Kotlin nullable" 원칙을 **그대로 적용하면 안 되는 자리가 있다.**
Kotlin nullability 가 Spring MVC 의 required 판정(`MethodParameter.isOptional()`)에 개입하기 때문이다.

| 파라미터 | Kotlin | 이유 |
|---|---|---|
| `@Valid @RequestBody` 5곳 | **non-null** | nullable 이면 Spring 이 body 를 선택 사항으로 해석 — 원본의 "body 누락 → `HttpMessageNotReadableException`" 이 null 통과로 바뀐다 |
| `@CookieValue(required = false)` | `String?` | 쿠키가 없으면 Spring 이 null 을 주입한다 |
| `@AuthenticationPrincipal` | `Long?` | 원본 boxed `Long`, principal 미존재 시 null 전달 |
| `HttpServletRequest`/`Response` | non-null | Spring MVC 가 항상 주입한다 |

## body 누락·malformed JSON 은 500 이 원본 계약이다 🔴

테스트 초안은 body 누락을 400 으로 가정했다가 500 을 받았다. 회귀인지 확인하기 위해 **원본
Java(`6515742`)를 임시 detached worktree 에서 실측**한 결과: body 누락 500 / malformed JSON 500 /
빈 객체 `{}` 400. 전역 핸들러가 `HttpMessageNotReadableException` 을 따로 다루지 않아 generic
`Exception` → 500 으로 떨어지는 **기존 동작**이다. 전환은 이를 그대로 보존하고(위 non-null 결정이
바로 이 보존이다), 개선(400 매핑 추가)은 담당 범위 밖이라 하지 않았다.

## 쿠키 계약 (그대로)

- `refreshToken`: HttpOnly / Secure=`auth.cookie.secure` / SameSite=Lax / Path=`/` / host-only.
  login autoLogin=true·reissue·OAuth login 은 설정 Max-Age 영속, autoLogin=false 는 세션 쿠키,
  logout 은 빈 값 + Max-Age 0. `Set-Cookie` 헤더 방식.
- `oauth_bcid`: HttpOnly / Secure 동일 / SameSite=Lax / Path=`/api/auth/oauth` / host-only /
  설정 Max-Age. 있으면 재사용(값 유지, Max-Age 갱신 재전송), blank 는 미존재 취급, 없으면
  32바이트 URL-safe 무패딩 발급. **로그인 완료 endpoint 에서는 만들지 않고**(없으면
  `INVALID_OAUTH_STATE`) 완료 후에도 지우지 않는다(다른 탭 보호). 원문은 service 로 가지 않고
  SHA-256 hex 만 전달한다.
- OAuth 4개 endpoint 의 쿠키 계약은 기존 테스트에 없었다 — `AuthControllerCookieContractTest` 가
  처음 고정한다.

## 유지한 나머지 계약

- provider별 client 고정: kakao endpoint → `KakaoOAuthClient`, google → `GoogleOAuthClient`
  (사용자 입력·enum 파싱으로 바꾸지 않음, bean 동일성까지 테스트로 고정)
- X-Forwarded-For 첫 값 `trim`, null/blank 면 `remoteAddr`, User-Agent 그대로 — 신뢰 정책을
  강화하지 않았다(동의 이력 증적용이라는 원본 판단 유지)
- SHA-256 + `HexFormat` + 예외 메시지, `String.getBytes()` 의 플랫폼 charset 의미는
  `toByteArray(Charset.defaultCharset())` 로 유지 (6단계와 같은 계열)
- 상태코드·메시지: 회원가입 201, 이메일 인증 요청 201, 나머지 200. 비밀번호 재설정 요청의
  중립 메시지(계정 존재 비노출)는 상수 그대로.
- `ApiResponse.success` overload 사용처 동일. 단 PasswordReset 의 `success(message, null)` 은
  Kotlin 제네릭상 `T = Void` 에 null 을 못 넘겨 반환 선언을 `ApiResponse<Void?>` 로 했다 —
  JVM generic signature 는 `Ljava/lang/Void;` 로 동일 소거되어 Java 원본과 시그니처·JSON·OpenAPI 가
  같다(제네릭 반환 타입 테스트 + api-docs diff 로 확인).

## 버린 대안

- `@RequestBody` nullable 통일 — Spring required 의미가 바뀌어 폐기 (위 🔴)
- `requireNotNull` 진입 가드 — 예외 종류가 바뀌므로 컨트롤러에는 두지 않음
- 쿠키 유틸 공통화·XFF 파서 개선·provider enum 통합 endpoint — 순수 전환 범위 밖

## 추가 테스트 (6파일 43건)

`AuthControllerJvmSurfaceTest`(12) · `AuthControllerRequestMappingContractTest`(3) ·
`AuthControllerCookieContractTest`(8) · `AuthControllerOrchestrationContractTest`(12) ·
`AuthSimpleControllerContractTest`(4) · `AuthControllerValidationSmokeTest`(4).
smoke 는 endpoint 12개가 실제 context 에서 전부 요청을 받는 것(404/405 아님)까지 고정한다.
Mockito 매처의 Kotlin non-null 파라미터 충돌은 6단계와 같은 typed helper 로 우회했다.

## 검증

- baseline(전환 전, 최신 develop): `test` 923/0/0/0, `integrationTest` 44/0/비활성 1, concurrency 2/2,
  `bootJar` 성공 — 1차 `integrationTest` 에서 Redis command timeout 1건(인프라 flake)이 있었고
  파일 무수정 상태 재실행에서 전건 통과를 확인한 뒤 진행했다
- 최종: `test` **966**(신규 43) / 실패·오류·skip 0, auth 420, `integrationTest` 44/0/비활성 1
  (기존 `OAuthEndpointRateLimitOrderTest`, 무수정), concurrency 2/2, compile 4종·`bootJar` 성공,
  strict ktlint(1.5.0) 위반 0
- Spring context 기동·controller bean 3개·mapping 12개 등록은 실제 context 테스트로,
  포트 bind 는 기존 `ChatWebSocketTest`(RANDOM_PORT) 통과로 확인
- 외부 OAuth provider·SMTP·운영 credential 미사용 (테스트 값 전부 명백한 더미)

---

# PR H — Java 테스트 전환 (회귀 안전망 세대교체)

브랜치 `feature/auth_kotlin_tests` / 기준 PR G(`feature/auth_kotlin_controller`) HEAD.
main 은 PR G 로 auth Java 0개가 됐지만, 1~7단계 내내 **회귀 안전망 역할이라 의도적으로 Java 로
남겨둔 기존 테스트 23개**가 마지막 잔여였다. 그중 22개(약 4,100줄)를 전환했다.

## 제외 1개 — `AuthKotlinInteropCompatibilityTest` 는 Java 로 남긴다

이 테스트의 검증 수단은 **"Java 소스에서 그 호출이 컴파일된다"는 사실 자체**다
(record 접근자 이름 유지, `isXxx()` getter 유지 등 — PR A 절 참고). Kotlin 으로 옮기는 순간
검증 대상이 사라진다. 도메인 내 유일한 의도적 Java 잔존이며, 파일 상단 주석에 사유를 남겼다.

## 원칙 — main 전환과 무엇이 다른가

테스트는 공개 API 가 아니므로 `javap` 표면 비교가 성립하지 않는다. 대신 게이트를 이렇게 정했다.

1. **1:1 전환**: 테스트 메서드명·`@DisplayName`·한국어 주석·구획 주석·검증 값 전부 보존.
   이름 백틱화·구조 개선·단언 강화 금지(안전망을 바꾸면서 안전망을 믿을 수는 없다).
2. **클래스별 테스트 수 baseline 대조**: 전환 전 XML 리포트에서 auth 전 클래스의 (클래스, 테스트 수)를
   떠 두고, 전환 후 동일 방식으로 재추출해 diff. 총합만 보면 "한 클래스에서 빠지고 다른 클래스에서
   늘어난" 종류의 손실을 놓친다.
3. `@Tag("integration")`·`@Disabled`(사유 문자열 포함) 등 어노테이션 바이트 단위 보존 —
   `integrationTest` 태스크가 태그로 선별하므로 태그 손실은 **조용한 테스트 누락**이 된다.

## 실제 발견 문제 — 컴파일에서 잡힌 3종 4건

| 위치 | 문제 | 처리 |
|---|---|---|
| `AuthControllerTest` | `response.getCookie()` 가 `Cookie?` — Java 는 단언 후 바로 접근 가능했다 | 직전 `isNotNull()` 단언이 있으므로 `!!` (동작 동일) |
| `RedisEmailVerificationCode`·`RedisPasswordResetToken` RepositoryTest | `.get().satisfies { }` 의 SAM+vararg 오버로드를 Kotlin 이 추론하지 못함 | 단일 오버로드 `hasValueSatisfying` 으로 — 빈 Optional 실패 의미 동일 |
| `AuthServiceTest` | `.extracting(메서드참조)` 가 vararg(`Tuple`) 오버로드로 풀려 타입 불일치 | 명시적 제네릭 `.extracting<AgreementType>(...)` 으로 단일 Function 오버로드 강제 |

## 실제 발견 문제 — 런타임 연쇄 실패 🔴 (문서화된 함정을 그대로 밟았다)

`PasswordResetControllerTest` 4건이 한꺼번에 깨졌다.

```
java.lang.NullPointerException: cap(...) must not be null
  → InvalidUseOfMatchersException / TooManyActualInvocations (2차 오류)
```

원인: `val bodyCaptor = ArgumentCaptor.forClass(String::class.java)` — **명시적 Kotlin 타입 없이**
선언해 `forClass` 의 platform 제네릭이 `cap<T>` 추론을 오염시켰고, non-null `EmailSender.send(String)`
자리에서 호출부 `checkNotNullExpressionValue` 가 터졌다. NPE 가 매처 스택을 오염시켜 무관해 보이는
3건(`TooManyActualInvocations` 포함)이 연쇄로 깨지는, 6단계 기록과 동일한 실패 형태다.

이건 **6단계 「테스트 작성 관행」절이 이미 경고한 함정**이다 — "captor 변수의 명시적 Kotlin 타입 선언"
한 줄을 빠뜨리면 컴파일은 통과하고 런타임에만 죽는다는 것을 재확인했다.
수정: `val bodyCaptor: ArgumentCaptor<String> = ...` 한 줄. 4건 전부 복구.

## 파일별 주요 판단 (전부 언어 경계가 강제한 것)

- **Mockito 헬퍼는 필요한 곳에만**: 매처 사용처 전수 감사 결과 대부분의 대상 파라미터가
  nullable(`String?` 등) 또는 Java 잔존(member)의 platform 타입이라 표준 `any()` 로 충분했다.
  helper 가 실제 필요한 곳은 non-null `EmailSender.send` 를 verify 하는 2개 파일의 `cap()` 뿐
  (`PasswordResetControllerTest`·`PasswordResetServiceTest`). 불필요한 파일에 복사하지 않았다(죽은 코드).
- **BDD 스타일 유지**: `AuthServiceTest` 는 원본이 `BDDMockito.given` 일색이라 그대로 —
  백틱 `` `when` `` 으로 갈아타지 않았다(1:1 원칙).
- **Testcontainers static → companion object**: `@Container val` 은 static 필드로 내려가 확장이
  그대로 찾고, `@DynamicPropertySource` 만 `@JvmStatic` 필요(진짜 static 메서드 요건).
- **record 접근자 → 프로퍼티**: `identity.provider()` → `identity.provider` 등 —
  대상이 Kotlin 이 된 데 따른 자연 변환, JVM 상 동일 접근자 호출.
- **박싱/승격 명시화**: Java 의 암묵 `int→long` 확대는 `.toLong()` 으로, `Long.class` 는
  `Long::class.javaObjectType` 으로 (`Long::class.java` 는 primitive `long.class` 가 되어 오판).
- **Java text block → raw string + `trimIndent()`**: text block 이 갖던 말미 개행 1개가 사라진다.
  JSON 본문·SQL 로만 쓰여 파싱 의미 동일 — 허용 차이로 기록. JSON 페이로드는 바이트 보존을 위해
  장문 단일행 raw string 을 허용했다(ktlint max-line-length 경고보다 페이로드 보존 우선).

## 검증

- baseline(전환 전): `test` 966/0/0/0 · `integrationTest` 44/실패 0/비활성 1 — XML 리포트에서
  auth 클래스별 테스트 수 전수 확보
- 최종: `test` **966**/0/0/0 · `integrationTest` **44**/0/비활성 1(`OAuthEndpointRateLimitOrderTest`,
  `@Disabled` 사유 문자열까지 보존) — **클래스별 테스트 수 diff 0**
- 전환 파일 ktlint 위반 0 · compile 전 소스셋 · `bootJar` 성공
- 남은 auth Java: `AuthKotlinInteropCompatibilityTest` 1개 (위 사유로 의도적 유지)
