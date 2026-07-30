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

---

# PR B — Persistence *(예정 — 미착수)*

> 아래 PR B~E 절은 **확정된 구현 기록이 아니라 검토 예정 항목 목록**이다.
> 각 PR 을 실제로 수행한 뒤 위 PR A 와 같은 양식(11개 필드)으로 이 문서에 누적한다.

브랜치 `refactor/kotlin-auth-02-persistence` / entity 3 + repository interface 8 + 구현 10

검토 예정 항목:
- Kotlin JPA entity 를 `data class` 로 만들면 안 되는 이유 (지연 로딩 프록시·JPA 동일성)
- `equals`/`hashCode` 를 새로 정의하지 않은 이유 (현재 3개 entity 모두 미정의 = 참조 동등성)
- JPA field access ↔ property access 차이
- `@field:Column` 등 use-site target (누락 시 **JPA 가 매핑을 조용히 무시**)
- nullable DB 컬럼 ↔ Kotlin 타입 (스키마와 1:1 일치, Flyway `validate` 와 어긋나지 않게)
- Java `Optional` ↔ Kotlin nullable (**현재 27곳** — interface·구현을 같은 PR 에서 동시 전환)
- `@Profile("test")` / `@Profile("!test")` 구현 **5쌍의 대칭** 유지
- static factory (`RefreshToken.issue`, `MemberSocialAccount.of`) 의 Java 호환성
- ⚠️ **`auth:refresh:{memberId}` Redis 키 형식·TTL 불변** 확인 방법

> **PR A 교훈 적용(예정)**: entity 는 `Member`(아직 Java) 를 참조하므로 nullability 를 조이지 말 것.
>
> **미검증 — PR B 에서 확인할 항목**: `backend/backend.md` 에는 allOpen 이 프로퍼티도 open 으로 만들어
> `private set` 이 컴파일 에러가 된다고 기록되어 있다(PR #17 작성자가 겪은 내용). PR A 는 entity 를
> 다루지 않아 **직접 확인하지 못했다.** PR B 에서 실제 컴파일로 확인한 뒤 결과를 이 문서에 기록한다.

# PR C — OAuth *(예정 — 미착수)*

검토 예정 항목: WebClient 응답 body nullability / 외부 API 오류 매핑(`OAuthClientErrorMapper` 의 예외 타입·발생 시점 보존) /
Google nonce ↔ 카카오 nonce 차이 / record·value object 호환성 / MockWebServer 기반 4xx·5xx·timeout 재현

# PR D — Service *(예정 — 미착수)*

검토 예정 항목: Kotlin `@Transactional` 과 all-open / **`Propagation.NOT_SUPPORTED` 전파 유지**(`AuthService.oauthLogin`) /
Java 에서 Kotlin 서비스 호출 시 nullability / 동시 가입 예외 처리(`DataIntegrityViolationException` → `reconcileAfterConflict`) /
fail-open(로그아웃) ↔ fail-closed(로그인·재발급) 정책 보존 / 공개 메서드 JVM 시그니처 유지
(특히 `RefreshTokenService` 는 `MemberService.java` 가 호출하는 **유일한 외부 진입점**)

# PR E — Controller *(예정 — 미착수)*

검토 예정 항목: Kotlin controller 파라미터 nullability / `@CookieValue(required = false)` /
`@AuthenticationPrincipal Long` 바인딩 / **HttpOnly 쿠키 계약**(이름·Path·SameSite·Max-Age) /
Jackson request binding / Bean Validation / 응답 JSON 계약
