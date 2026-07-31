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
- **주 생성자의 JPA 어노테이션은 `@field:`** — 생략하면 생성자 파라미터에 붙어 **JPA 가 매핑을 무시**한다.
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

## 주의

- **CORS 설정 없음** — 브라우저는 단일 origin만 호출하고 `/api`는 서버가 프록시한다.
- `global/` 공통 구조(SecurityConfig·공통 응답/에러)는 팀장 영역이다.
- 새 도메인을 추가하면 이 문서의 도메인 지도를 **같은 PR에서** 갱신한다.
