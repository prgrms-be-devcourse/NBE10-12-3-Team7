# backend — Spring Boot API 서버

마켓온의 API 서버. **Spring Boot 3.5 / Java 21 + Kotlin 2.2**, 도메인별 패키지 + 계층형 구조.

> 🔄 **Java → Kotlin 마이그레이션 진행 중.** 두 언어가 공존한다 —
> `src/main/java`(남은 것)와 `src/main/kotlin`(전환된 것)을 함께 컴파일한다.
> 전환이 끝나면 `java` 소스셋을 삭제한다. 진행 상황과 규칙은 아래 [Kotlin 마이그레이션](#kotlin-마이그레이션) 참고.

> 이 문서는 `backend/`를 이해하는 진입점이다. 리포 전체는 [../README.md](../README.md), 에이전트 작업 규칙은 [../AGENTS.md](../AGENTS.md).

## 스택

| | |
|---|---|
| 프레임워크 | Spring Boot 3.5, Java 21 + **Kotlin 2.2**(전환 중, 공존) |
| 보안 | Spring Security + JWT |
| 영속성 | JPA(Hibernate), MySQL 8 / 테스트는 H2 |
| 스키마 | **Flyway** 마이그레이션 (`src/main/resources/db/migration/`). 운영은 `ddl-auto: validate` |
| 문서 | Springdoc(Swagger) — 코드에서 자동 생성 |
| AI | Spring AI + Ollama (관리자 AI 어시스턴트 `admin/ai`) |

## 도메인 지도

패키지 루트는 `com.dongnemarket` — 전환 여부에 따라 `src/main/java/com/dongnemarket/`
또는 `src/main/kotlin/com/dongnemarket/` 에 있다(패키지 경로는 동일).
**작업은 자기 도메인 패키지 안에서만** 한다.

| 도메인 | API 베이스 | 책임 |
|---|---|---|
| `auth` | `/api/auth`, `/api/auth/email-verifications`, `/api/auth/password-resets` | 회원가입·로그인·JWT·이메일 인증·비밀번호 재설정 |
| `member` | `/api/members`, `/api/members/me/locations` | 내 정보·동네(지역) 설정 |
| `product` | `/api/products`, `/api/products/me` | 상품·이미지·검색·노출 우선순위 |
| `category` | `/api/categories` | 카테고리 |
| `region` | `/api/regions` | **계층형 지역 마스터**(시-구-동), `regionCode` 기준 |
| `favorite` | — | 찜. 이벤트로 `Product.favoriteCount` 증감 |
| `comment` | — | 댓글 |
| `report` | — | 신고 |
| `notification` | — | 알림(댓글·가격변경·채팅). **저장형이 아니라 조회 시점 파생** |
| `chat` | — | 1:1 채팅 |
| `trade` | — | 거래 내역 |
| `escrow` | `/api/escrows` | **안심결제(에스크로)** — 단일 Trade 애그리거트, 실송금은 모사 |
| `manner` | — | 매너온도 |
| `admin` | `/api/admin/*` (ai · members · products · comments · reports · dashboard · manner-scores · storage) | 운영 관리 + AI 어시스턴트 |
| `global` | — | **공통(팀장 소유)** — 응답·예외·보안·설정·시더 |

## 계층 규칙

```
Controller → Service → Repository → Entity/DTO
```

- **Controller** — 요청 수신, `@Valid`, `ApiResponse` 반환, Swagger 어노테이션. **비즈니스 로직 금지.**
- **Service** — 비즈니스 로직·검증·트랜잭션. 예외는 `BusinessException(ErrorCode)`.
- **Repository** — DB 접근만.
- **Entity를 API 응답으로 직접 반환 금지** — Request/Response DTO를 분리한다.

## Kotlin 마이그레이션

Java → Kotlin 전환을 도메인 단위로 진행한다. 전환된 파일은 `src/main/kotlin`, 남은 파일은
`src/main/java` — 두 소스셋을 함께 컴파일하므로 **한 파일씩 옮길 수 있다.**

실제 변환 사례, 실패 원인과 검증 방법은
[Auth Java → Kotlin 마이그레이션 기록](docs/kotlin-migration/auth-migration-notes.md)에 누적한다.

### 빌드 설정 (팀장만 수정)

`build.gradle` 의 Kotlin 플러그인은 전부 "Kotlin 클래스가 기본 `final`"이라 생기는 문제를 푸는 장치다.

| 플러그인 | 없으면 |
|---|---|
| `plugin.spring` (allopen) | CGLIB 프록시 불가 → **`@Transactional` 이 런타임에 조용히 안 걸린다** |
| `plugin.jpa` (noarg) | 엔티티 기본 생성자 없음 → JPA 인스턴스화 실패 |
| `allOpen { @Entity, @MappedSuperclass, @Embeddable }` | 엔티티가 `final` → **지연 로딩 프록시 실패** |
| `jackson-module-kotlin` | `data class` DTO 역직렬화 실패 |

### 전환 시 규칙

- **엔티티에 `data class` 금지** — `equals`/`hashCode` 가 지연 로딩을 건드리고 JPA 동일성과 어긋난다. 항상 `class`.
- **주 생성자에는 `@field:` 를 기본으로 붙인다.** Kotlin 은 use-site target 을 생략하면
  `param → property → field` 중 **그 어노테이션의 `@Target` 이 허용하는** 첫 자리를 고른다.
  - JPA(`@Column` `@Id` `@ManyToOne` 등)는 `@Target` 이 `{METHOD, FIELD}` 로 **PARAMETER 를 허용하지 않아
    `@field:` 없이도 필드에 붙는다.** 붙여도 무해하다.
  - ⚠️ **위험한 쪽은 검증·Jackson 어노테이션이다.** `@NotBlank` `@NotNull` `@Size` `@JsonProperty` 는
    `@Target` 에 PARAMETER 가 있어 **생성자 파라미터가 우선 선택**된다. 검증은 필드/getter 를 읽으므로
    DTO 를 `data class` 로 옮길 때 `@field:NotBlank` 로 명시하지 않으면 **검증이 걸리지 않을 수 있다.**
    (현재 검증 어노테이션 58곳 / 22파일 — 첫 DTO 전환 시 "빈 문자열 → 400" 테스트로 확인할 것)
  - 어노테이션마다 `@Target` 을 찾아보지 않아도 되도록 **엔티티·DTO 주 생성자는 `@field:` 로 통일**한다.
- **값을 주입받는 어노테이션은 `@param:`** — `@Value` 는 PARAMETER 를 허용하며 생성자 주입에서는 그게 맞다.
- **엔티티 프로퍼티는 `private set` 대신 `protected set`** — allOpen 이 프로퍼티도 open 으로 만들어
  Kotlin 이 open 프로퍼티의 private setter 를 금지한다(컴파일 에러).
- **Java 에서 호출되는 팩토리에 `@JvmStatic`**, 기본 인자가 있는 생성자/함수에 **`@JvmOverloads`**.
  없으면 Java 호출부가 깨진다(`Foo.Companion.bar()` / 인자 적은 호출 불가).
- **공개 API 의 nullability 를 임의로 조이지 않는다.** non-null `Long` 은 primitive `long` 이 되어
  아직 Java 인 호출부에서 **자동 언박싱 NPE** 를 낸다. 원본 시그니처를 그대로 옮기고, 전환 완료 후 별도 패스에서 조인다.
- **Java `record` 는 `@JvmRecord data class` 로 옮긴다.** 그냥 `data class` 로 바꾸면 **접근자 이름이
  바뀌어**(`productId()` → `getProductId()`) 아직 Java 인 호출부가 전부 깨진다. `@JvmRecord` 를 붙이면
  JVM 상에서도 진짜 record 라 `productId()` 가 그대로 유지된다(호출부를 기계적으로 고치지 않아도 된다).
  깨진 호출부는 grep 말고 `./gradlew compileJava` 로 찾는다.
- **`equals`/`hashCode` 가 없던 일반 DTO 는 `data class` 로 바꾸지 않는다.** 값 기반 동등성·`copy`·
  `componentN` 이 새로 생겨 **기존에 없던 동작이 추가**된다. 일반 `class` 로 옮긴다.
- **boolean getter 이름은 `@get:JvmName` 으로 유지한다.** Kotlin 은 `val autoLogin` 을 `getAutoLogin()`
  으로 만들지만 Java 호출부는 `isAutoLogin()` 을 쓴다. 프로퍼티 이름을 `isAutoLogin` 으로 바꾸는 우회는
  **쓰지 않는다** — JSON 필드명까지 `isAutoLogin` 으로 바뀐다.
- **직렬화되는 프로퍼티에 `@get:JvmName` 을 쓰면 `@get:JsonProperty` 도 함께 붙인다.** getter 이름을
  바꾸면 jackson-module-kotlin 이 정하는 JSON 필드명이 원본과 달라진다(실제로 응답 필드가 사라져 테스트가 깨졌다).

### 테스트 전환 시 규칙

- **테스트 이름은 백틱으로 감싼 한글 문장.** `@DisplayName` 은 `@Nested` 클래스에만 남긴다.

- **`@Nested` 를 붙일 클래스는 `inner class` 로 선언한다.**
  Kotlin 은 클래스 안에 클래스를 쓰면 기본이 "바깥과 분리된 클래스"다. JUnit 은 바깥 클래스와
  이어진 것만 중첩 테스트로 인정하므로, `inner` 를 붙여야 테스트가 실행된다.

- **`static` 이 필요한 것은 `companion object` 안에 넣고 `@JvmStatic` 을 붙인다.**
  Kotlin 에는 `static` 이 없어서 Java 쪽에 static 으로 보이게 만들어줘야 한다.
  - `@DynamicPropertySource` 는 static 메서드만 인식한다 → `@JvmStatic` 필요
  - static `@TempDir` 은 `@field:TempDir` 까지 붙여야 한다. 안 붙이면 어노테이션이 엉뚱한
    자리에 걸려 디렉터리가 안 만들어진다.

- **Mockito 의 `any()` 를 Kotlin 에서 그냥 쓰면 터진다.**
  `any()` 는 "아무 값이나 매칭" 표시를 남기고 실제로는 **null 을 돌려준다.** 그런데 Kotlin 은
  null 이 들어오면 안 되는 자리에 null 검사 코드를 자동으로 넣기 때문에,
  `IllegalStateException: any() must not be null` 로 즉시 실패한다.
  타입을 한 번 우회시키는 헬퍼로 검사를 피한다.

  ```kotlin
  @Suppress("UNCHECKED_CAST")
  private fun <T> anyValid(): T { ArgumentMatchers.any<T>(); return null as T }
  ```

  단, **Java 로 작성된 메서드의 파라미터**에는 그냥 `any()` 를 써도 된다. Kotlin 이 Java 코드의
  null 허용 여부를 알 수 없어 검사 코드를 넣지 않기 때문이다.

- **메서드 자체에 타입 파라미터가 붙은 API 는 Kotlin 이 타입을 못 맞춘다.**
  예: `JpaSpecificationExecutor.findBy`. 인자 자리에서 `anyValid()` 의 타입을 추론하지 못해
  컴파일이 안 된다. 호출 한 벌을 확장 함수로 만들어 **stub 할 때와 verify 할 때가 같은 형태**를
  쓰게 고정한다. 이때 반환 타입은 nullable(`?`)로 둔다 — 실제로 값을 받는 호출이 아니라
  "이렇게 부를 거야"라고 표시만 하는 호출이라 null 이 온다.

- **assertj 의 `extracting` 은 Kotlin 에서 쓰지 않는다.**
  `extracting` 은 이름이 같은 메서드가 여러 개라 Kotlin 이 어느 것을 부를지 못 고른다.
  `map { it.title }` 로 먼저 뽑고 단언한다. 결과는 동일하다.

- **번역이지 개선이 아니다.** 단언·픽스처·teardown 순서를 원본 그대로 옮긴다.
  다 옮긴 뒤 `@Test` 개수와 단언 개수를 Java 원본과 세어서 맞춰본다.

### 절차

```bash
# 1) .kt 작성 → 2) 해당 .java 삭제 → 3) 컴파일러가 깨진 호출부를 알려준다
./gradlew compileKotlin compileJava
./gradlew test                # 기존 테스트가 동작 등가성을 검증한다
./gradlew ktlintFormat        # 포매팅 자동 교정 (ktlint 는 현재 경고만)
```

진척 확인:

```bash
find src/main/java -name '*.java' | wc -l    # 남은 것
find src/main/kotlin -name '*.kt' | wc -l    # 전환된 것
```

## 정본 (지어내지 말고 여기서 확인)

| 알고 싶은 것 | 어디를 보나 |
|---|---|
| API 요청/응답 스키마 | **Swagger** `http://localhost:8080/swagger-ui.html` (코드에서 자동 생성) |
| 성공/에러 응답 형식 | `global/response/ApiResponse`, `global/exception/` |
| 에러 코드 목록 | `global/exception/ErrorCode` |
| 스키마·테이블 구조 | `src/main/resources/db/migration/V*.sql` |
| 시드 데이터 | `global/init/`, `src/main/resources/seed/` |

## 실행

```bash
docker compose up -d --wait     # 리포 루트에서 — MySQL만 기동
./gradlew bootRun               # :8080
```

## 테스트

```bash
./gradlew test                          # 단위 + 슬라이스 (H2)
./gradlew test --tests "com.dongnemarket.favorite.*"   # 도메인별
./gradlew clean build -x test           # 앱 이미지용 JAR
```

- 단위 테스트는 목킹 기반, 통합 테스트는 `@SpringBootTest`·`@DataJpaTest`.
- 통합 테스트는 "코드만 읽어도 흐름이 보이는" 실행 명세서로 쓴다 — GWT 구조, 시나리오 네이밍, 실제 사용자 여정.
- **테스트마다 H2 데이터베이스가 따로 뜬다.** 설정은 `application-test.yml` 한 줄이다.

  ```yaml
  url: jdbc:h2:mem:dongne_test-${random.uuid};MODE=MySQL;...
  ```

  `${random.uuid}` 는 스프링이 테스트용 앱을 **띄울 때 한 번** 값을 정한다. 그래서 설정이 같은
  테스트끼리는 앱을 재사용하니까 DB 도 하나를 같이 쓰고, 설정이 다르면 DB 가 따로 생긴다.

  **왜 이렇게 하나** — 테스트 설정은 `ddl-auto: create-drop` 이라, 스프링이 테스트용 앱을 띄울
  때마다 **테이블을 전부 지우고 다시 만든다.** 예전처럼 DB 이름을 하나로 고정하면, 나중에 뜬 앱이
  앞서 넣어둔 기본 데이터(지역 5,338건·카테고리 8건)를 통째로 날린다. `@DataJpaTest` 는 DB 관련
  빈만 골라 띄우는 방식이라 데이터를 넣어주는 시더가 없어서, 한 번 날아가면 아무도 다시 채워주지 않는다.

  그 결과 **전체 테스트의 성공 여부가 "어떤 테스트 클래스가 먼저 도느냐"에 좌우됐다.** 실제로
  테스트 파일을 `src/test/java` 에서 `src/test/kotlin` 으로 옮겼더니 실행 순서가 바뀌면서,
  코드는 한 줄도 안 건드렸는데 알림 테스트 12개가 깨진 적이 있다.

  **하지 말 것** — 클래스마다 `@TestPropertySource` 로 DB 이름을 따로 지정하는 방식.
  새 `@DataJpaTest` 가 생길 때마다 같은 사고가 반복된다. 이제 필요 없다.

## 주의

- **CORS 설정 없음** — 브라우저는 단일 origin만 호출하고 `/api`는 서버가 프록시한다.
- `global/` 공통 구조(SecurityConfig·공통 응답/에러)는 팀장 영역이다.
- 새 도메인을 추가하면 이 문서의 도메인 지도를 **같은 PR에서** 갱신한다.
