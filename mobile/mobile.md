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
│   ├── mapper/       DTO ↔ 도메인 모델 변환
│   └── repository/   Repository 구현
├── domain/
│   ├── model/        도메인 모델 (UI가 쓰는 형태)
│   └── repository/   Repository 인터페이스 (data가 구현)
├── di/               Hilt 모듈
└── ui/
    ├── login/ home/ productdetail/ chat/    화면별 Composable + ViewModel
    ├── component/    공용 컴포넌트
    ├── navigation/   화면 이동
    └── theme/        테마
```

의존 방향은 **`ui → domain ← data`** 다. `ui`는 `domain`의 인터페이스만 알고 `data` 구현을 모른다.

## 화면 (Phase 1)

| 화면 | 내용 |
|---|---|
| `login` | 로그인 |
| `home` | 상품 목록 |
| `productdetail` | 상품 상세 |
| `chat` | 채팅 목록 · 채팅방 |

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

### 로컬 백엔드 (앱 검수용)
```bash
docker compose up -d mysql redis
MAIL_USERNAME=noreply@localhost MAIL_PASSWORD=dummy \
  backend/gradlew -p backend bootRun --args='--spring.profiles.active=dev,demo'
```
> `MAIL_USERNAME` 이 없으면 **기동 자체가 실패**한다(`SmtpEmailSender` 가 필수 빈).
> `demo` 는 `ddl-auto: create` 라 스키마를 드롭·재시딩한다.

debug 빌드는 `http://10.0.2.2:8080` (에뮬레이터가 보는 PC의 localhost)을 본다.

## 주의

- API 스펙의 정본은 백엔드 **Swagger**다. DTO를 손으로 작성하므로 백엔드 변경 시 어긋날 수 있다.
- 화면·계층이 추가되면 이 문서의 구조를 **같은 PR에서** 갱신한다.
- 현재 백엔드 API **81개 중 18개(22%)** 만 소비한다. 상품 등록·회원가입·찜목록·내정보·알림·에스크로·경매는 미구현이다.
