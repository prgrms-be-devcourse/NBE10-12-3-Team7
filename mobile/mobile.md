# mobile — Android 네이티브 클라이언트

마켓온 Android 앱. **Kotlin + Jetpack Compose**, 백엔드 API를 그대로 사용하는 독립 클라이언트다.

> 이 문서는 `mobile/`를 이해하는 진입점이다. 리포 전체는 [../README.md](../README.md).
> **백엔드 불가침** — 모바일 작업으로 `backend/`를 수정하지 않는다. API가 부족하면 백엔드 쪽에 별도로 요청한다.

## 스택

| | |
|---|---|
| 언어 | Kotlin |
| UI | Jetpack Compose |
| DI | Hilt |
| 네트워크 | Retrofit (API 인터페이스는 손으로 작성) |
| 빌드 | Gradle (Kotlin DSL, `libs.versions.toml` 버전 카탈로그) |

## 구조 — 3계층

```
mobile/app/src/main/java/com/dongnemarket/mobile/
├── data/
│   ├── remote/       Retrofit API 인터페이스 · DTO
│   ├── local/        로컬 저장 (토큰 등)
│   ├── image/        사진 리사이즈·JPEG 압축·EXIF 회전 (업로드 전처리)
│   ├── mapper/       DTO ↔ 도메인 모델 변환
│   └── repository/   Repository 구현
├── domain/
│   ├── model/        도메인 모델 (UI가 쓰는 형태)
│   └── repository/   Repository 인터페이스 (data가 구현)
├── di/               Hilt 모듈
└── ui/
    ├── login/ home/ productdetail/ chat/ productcreate/ region/    화면별 Composable + ViewModel
    ├── component/    공용 컴포넌트
    ├── navigation/   화면 이동
    └── theme/        테마
```

의존 방향은 **`ui → domain ← data`** 다. `ui`는 `domain`의 인터페이스만 알고 `data` 구현을 모른다.

## 화면

| 화면 | 내용 |
|---|---|
| `login` | 로그인 |
| `home` | 상품 목록 (+ 글쓰기 FAB) |
| `productdetail` | 상품 상세 |
| `chat` | 채팅 목록 · 채팅방 |
| `productcreate` | 상품 등록 |
| `region` | **내 동네 설정** |

## 상품 등록 — 2단계 요청

서버에 "이미지까지 한 번에" 받는 엔드포인트가 없어 요청이 갈라진다.

```
POST /api/products/images   장당 1회, multipart @RequestPart("files")  → 경로 수집
POST /api/products          그 경로들을 imageUrls 에 실어 전송
```

`ProductRepository.createProduct()` 가 이 순서를 감춘다 — 화면은 호출 하나만 안다.

**장당 1회로 나눠 보내는 이유**: 서버의 `max-request-size` 가 `max-file-size` 와 똑같이 5MB 라
여러 장을 한 요청에 담으면 파일 검증에 닿기도 전에 요청이 잘린다.
업로드 전에 `ImageCompressor` 가 긴 변 1440px · JPEG 로 굽고 EXIF 회전도 픽셀에 적용한다.

**지역**: `regionCode` 는 읍면동(level 3)만 받는다. 내 동네 설정도 서버가 같은 제약을 걸어서
`GET /api/members/me/locations` 결과를 그대로 쓴다(변환·재검증 불필요).
동네 미설정 계정은 등록할 수 없어 안내 + **동네 설정 화면으로 가는 버튼**을 띄운다.

> ⚠️ 서버가 `description` 을 검증 없이 역참조한다(`request.description!!`) → null 이면 **500**.
> 앱은 미입력이어도 빈 문자열을 보낸다. `explicitNulls = false` 라 null 이면 키가 통째로 빠지기 때문이다.

## 지역(region) 표현

백엔드가 2026-07 지역 모델을 개편해 응답의 `region: String` 하나가
`regionCode` · `regionName` · `regionFullName` **셋으로 쪼개졌고, 통신 값이 이름 → 코드로 바뀌었다.**

앱은 이 셋을 값 객체 하나로 묶는다.

| 모델 | 무엇 | 어디서 |
|---|---|---|
| `RegionRef(code, name, fullName)` | 다른 응답에 **박혀 오는** 지역 참조 | 상품 · 찜 · 채팅 · 내 동네 |
| `Region(regionId, code, level, parentCode, fullName, displayName)` | 지역 **마스터** 목록(계층 포함) | `GET /api/regions` |

- 화면 표시는 `region.display`(짧은 이름, 비면 전체 이름)
- 서버 필터는 `region.code` — 쿼리 파라미터도 `regions` 가 아니라 **`regionCodes`**

### 내 동네 설정 — 드릴다운뿐이다

전국이 시·도 **16** + 시·군·구 **255** + 읍·면·동 **5,067** = 5,338건인데
서버가 주는 것은 `GET /api/regions?parentCode=` 하나뿐이고 **검색 API 도 페이징도 없다**
→ 한 단계씩 내려가는 것이 유일한 탐색 방법이다. `RegionRepository` 가 단계별로 캐시한다.

| 서버 규칙 | 앱이 하는 일 |
|---|---|
| **1~2개**, 중복 불가 | 상한에 닿으면 안내, 이미 고른 것 다시 누르면 해제 |
| **읍·면·동(level 3)만** | `Region.isSelectable` 로 판단 — 그 위는 꺾쇠(파고들기), 동은 체크(선택) |
| **리스트 0번이 대표** | 대표를 **순서**로 표현한다(칩을 누르면 맨 앞으로). 플래그를 따로 두지 않는다 |
| **전체 교체** | 화면 진입 시 **기존 내 동네를 초기 선택값으로 채운다** |

> ⚠️ 마지막 줄이 중요하다. 안 채우면 동네를 **추가**하려던 사용자가 하나만 고르고 저장하는 순간
> 원래 있던 다른 하나를 잃는다. 예외도 에러도 없이 사라진다.

## 빌드 · 테스트

`mobile/` 에는 `gradlew` 가 없다. Android Studio 또는 캐시된 배포판을 쓴다.

```bash
JAVA_HOME="<Android Studio>/jbr" gradle -p mobile :app:assembleDebug
JAVA_HOME="<Android Studio>/jbr" gradle -p mobile :app:testDebugUnitTest --rerun
```
> `--rerun` 이 없으면 Gradle 이 `UP-TO-DATE` 로 건너뛰어 **테스트가 돌지 않은 채 성공처럼 보인다.**

계기 테스트(Compose UI)는 에뮬레이터가 필요하다.
```bash
gradle -p mobile :app:connectedDebugAndroidTest
```

### 빌드 타입 — 바라보는 서버가 다르다

| 빌드 | BASE_URL | 서명 | 용도 |
|---|---|---|---|
| `debug` | `http://10.0.2.2:8080/` | debug 키 | **에뮬레이터 전용** |
| `lan` | `http://<PC-IP>:8080/` (빌드 시 주입) | debug 키 | **실제 폰**에서 확인 |
| `release` | `https://marketon.inyeon.io/` | 릴리스 키 | 배포 — 🔴 **지금 못 쓴다** |

`10.0.2.2` 는 **에뮬레이터가 호스트 PC 를 보는 가상 주소**다. 실제 폰에서는 동작하지 않는다.

> 🔴 **`release` 는 아직 배포에 쓸 수 없다.** AWS → 온프레미스 전환으로
> `marketon.inyeon.io` 의 DNS 가 사라졌다(`Non-existent domain`).
> HTTPS 도메인이 정해지면 이 값을 바꾼다 — [이슈 #114](https://github.com/prgrms-be-devcourse/NBE10-12-3-Team7/issues/114).
> 그때까지 팀 배포는 `lan` 빌드(같은 Wi-Fi)로만 가능하다.

```bash
# 폰용 — IP 는 저장소에 박지 않고 빌드할 때 넘긴다
gradle -p mobile :app:assembleLan -PlanHost=192.168.0.5:8080
```

`lan` 은 사설망 HTTP 라 `usesCleartextTraffic` 이 필요한데, 그 설정은
`src/lan/AndroidManifest.xml` 에만 둔다 → **`release` 는 평문 통신이 허용되지 않는다.**

### 릴리스 서명

`build.gradle.kts` 가 `local.properties`(gitignore) 또는 환경변수에서 읽는다.

```properties
RELEASE_STORE_FILE=/절대/경로/marketon-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=marketon
RELEASE_KEY_PASSWORD=...
```

키가 없어도 빌드는 깨지지 않는다(다른 팀원·CI 가 막히지 않도록). 대신 **서명되지 않은
설치 불가능한 APK** 가 나오므로 릴리스 빌드 시작 시 경고를 띄운다.

> 🔴 키를 잃으면 같은 앱을 다시 업데이트할 수 없다. `.jks` 와 비밀번호는 저장소 밖에 보관한다.

### 로컬 백엔드 (앱 검수용)
```bash
docker compose up -d mysql redis
MAIL_USERNAME=noreply@localhost MAIL_PASSWORD=dummy \
  backend/gradlew -p backend bootRun --args='--spring.profiles.active=dev'
```
> `MAIL_USERNAME` 이 없으면 **기동 자체가 실패**한다(`SmtpEmailSender` 가 필수 빈).
> 데모 시드가 필요하면 `app.seed.demo=true` 를 준다(`DemoDataSeeder`).

## 구현 현황

백엔드 엔드포인트 **83개** 중 관리자용 17개는 모바일 범위가 아니다 →
**소비자용 66개 중 21개(32%)** 를 소비한다.

| 도메인 | 소비 | 상태 |
|---|---|---|
| 채팅 | 5/5 | ✅ |
| 지역·내 동네 | 3/3 | ✅ |
| 찜 | 3/3 | ✅ |
| 상품 | 5/9 | 🟡 조회·등록만. **수정·삭제·상태변경·내 상품 목록 없음** |
| 카테고리 | 1/2 | 🟡 목록만 |
| 인증·계정 | 2/13 | 🔴 로그인·로그아웃만 |
| 이미지 | 1/2 | 🟡 업로드만 |
| 신고 | 0/7 | ⬜ |
| 거래·에스크로 | 0/7 | ⬜ |
| 댓글 | 0/4 | ⬜ |
| 알림 | 0/3 | ⬜ |
| 경매 | 0/3 | ⬜ |
| 매너온도 | 0/3 | ⬜ |

### ⚠️ 목표 대비 미달성

착수 시 목표는 "백엔드 API 를 소비해 모바일 앱으로 전환" 이었다. **전환은 3분의 1 지점에서 멈췄다.**
특히 아래 둘은 앱이 **제품으로 성립하지 못하게** 만드는 구멍이다.

| 미구현 | 왜 문제인가 |
|---|---|
| **회원가입** (`POST /api/auth/signup`) | 시드 계정으로만 앱을 쓸 수 있다. 새 사용자가 진입할 방법이 없다 |
| **내 상품 관리** (`GET /api/products/me`, `PATCH` 상태·수정, `DELETE`) | 등록만 되고 그 뒤가 없다. 올린 물건을 거래완료로 바꾸거나 지울 수 없다 |

그 밖에: 찜 목록·내정보 화면이 없어 **하단 탭 4개 중 2개가 비활성**이고,
신고·댓글·거래·경매·알림·매너온도는 착수하지 않았다.

## 주의

- API 스펙의 정본은 백엔드 **Swagger**다. DTO를 손으로 작성하므로 백엔드 변경 시 어긋날 수 있다.
- 화면·계층이 추가되면 이 문서의 구조를 **같은 PR에서** 갱신한다.
- **모바일은 CI 에 없다**(`.github/workflows/ci.yml`). 테스트는 로컬에서만 돈다.
