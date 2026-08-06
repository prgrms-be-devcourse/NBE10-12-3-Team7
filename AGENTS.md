# AGENTS.md — AI 에이전트 진입 문서

> **어떤 AI 에이전트든(Claude Code · Cursor · Gemini 등) 이 저장소에서 작업을 시작하기 전에 이 파일을 먼저 읽는다.**
> 이 문서는 프로젝트를 이해하고 개발 프로세스를 지키기 위한 단일 진입점이다.
> 세부는 **각 모듈 폴더의 문서**로 연결한다 — 문서는 코드 옆에 산다.

---

## 1. 프로젝트 한눈에

**마켓온** — 지역 기반 중고거래 서비스. 모노레포.

| 모듈 | 무엇 | 문서 |
|---|---|---|
| `backend/` | Spring Boot 3.5 / Kotlin 2.2 · Security(JWT) · JPA · MySQL 8 · Redis · Flyway · Swagger | [backend/backend.md](backend/backend.md) |
| `frontend/` | Next.js 16(App Router) · React 19 · TypeScript | [frontend/frontend.md](frontend/frontend.md) |
| `mobile/` | Kotlin · Jetpack Compose · Hilt · Retrofit | [mobile/mobile.md](mobile/mobile.md) |
| `agent/` | Python · LangGraph · Ollama(qwen3:4b) · FastAPI | [agent/agent.md](agent/agent.md) |
| `infra/` | Docker Compose · nginx · Prometheus·Loki·Grafana | [infra/infra.md](infra/infra.md) |

## 2. 코드 지도 (어디를 고치나)

백엔드 패키지 루트: `com.dongnemarket` (`backend/src/main/kotlin/com/dongnemarket/`).
**도메인별 패키지 + 계층형** 구조. 작업은 **자기 도메인 패키지 안에서만** 한다.

| 도메인 | 책임 |
|---|---|
| `auth` | 회원가입·로그인·JWT·이메일 인증·비밀번호 재설정·소셜 로그인(카카오·구글) |
| `member` | 내 정보·동네(지역) |
| `product` | 상품·이미지·검색·노출 우선순위 |
| `category` | 카테고리 |
| `region` | 계층형 지역 마스터(시-구-동), `regionCode` 기준 |
| `favorite` | 찜 (이벤트로 `Product.favoriteCount` 증감) |
| `comment` | 댓글 |
| `report` | 신고 |
| `notification` | 알림 — 저장형이 아니라 조회 시점 파생 |
| `chat` | 1:1 채팅 |
| `trade` | 거래 내역 |
| `escrow` | 안심결제(에스크로) |
| `auction` | 실시간 경매(딜) — STOMP 입찰 브로드캐스트, 스케줄러 마감 |
| `manner` | 매너온도 |
| `admin` (+`admin/ai/`) | 운영 관리 + AI 어시스턴트 |
| `global` | **공통(팀장 소유)** — 응답·예외·보안·설정 |

**계층 규칙**: `Controller → Service → Repository → Entity/DTO`
- Controller: 요청 수신·`@Valid`·`ApiResponse` 반환·Swagger. 비즈니스 로직 금지.
- Service: 비즈니스 로직·검증·트랜잭션. 예외는 `BusinessException(ErrorCode)`.
- Repository: DB 접근만. Entity는 응답으로 직접 반환 금지(DTO 분리).

도메인별 API 베이스 경로는 [backend/backend.md](backend/backend.md)에 있다.

## 3. 정본 (grounding — 지어내지 말고 여기서 확인)

| 알고 싶은 것 | 정본 |
|---|---|
| API 스펙(요청/응답 스키마) | **Swagger** `http://localhost:8080/swagger-ui.html` (코드에서 자동생성) |
| 성공/에러 응답 형식 | `backend/.../global/response/ApiResponse`, `global/exception/` |
| 스키마·테이블 구조 | `backend/src/main/resources/db/migration/V*.sql` |
| 모듈 구조·실행·테스트 | 각 모듈의 `<모듈>.md` (위 1번 표) |
| 배포·운영·CI/CD | [infra/infra.md](infra/infra.md) |
| 이 작업을 왜·어떻게 했나 | 해당 **PR 본문** (`gh pr view <n>`) — 머지된 PR이 작업 기록의 정본 |

**주요 명령** (리포 루트에서):
```bash
docker compose up -d --wait                    # MySQL·Redis·Ollama (dev)
cd backend && ./gradlew bootRun                # 백엔드 :8080
cd frontend && npm install && npm run dev      # 프론트 :3000
cd backend && ./gradlew test                   # 단위/슬라이스 테스트 (H2) — integration 태그 제외
cd backend && ./gradlew integrationTest        # 통합 테스트 (Testcontainers, Docker 필요)
```

## 4. 개발 흐름 (모든 기능 공통)

```
① 착수 전   → Notion WBS 'task 03.개발'에 기능 단위 "개발할 것" 문서 작성
② 브랜치    → develop에서 feature/{도메인}_{기능} 분기
③ 개발      → ErrorCode → 구현 → 단위 테스트 → 통합 테스트 → PR
④ PR        → feature/* → develop, 작게·자주. PR 템플릿 채움
⑤ 마무리    → 문서 영향 확인 후 갱신(아래 5번), develop은 항상 green 유지
```

커밋 타입: `feat` · `fix` · `docs` · `refactor` · `test` · `chore`.
브랜치는 `feature/{도메인}_{기능}`, PR은 develop 대상으로 작게 자주.

## 5. ★ 문서 동기화 규칙 (에이전트가 반드시 집행)

> 이 프로젝트의 최우선 과제: **코드가 앞서가고 문서가 뒤처지는 드리프트를 막는 것.**
> 코드를 변경한 **바로 그 작업(같은 PR)에서** 아래 매핑에 따라 문서를 함께 갱신한다. "나중에"는 없다.
> 문서를 코드 옆에 둔 이유가 이것이다 — 고칠 문서가 바로 옆에 있으면 미룰 핑계가 없다.

| 변경 종류 | 해야 할 문서 작업 |
|---|---|
| 새 **도메인·라우트·화면·계층** 추가 | 그 모듈의 `<모듈>.md` 지도 갱신 |
| 모듈의 **실행·테스트 방법** 변경 | 그 모듈의 `<모듈>.md` |
| **배포·인프라·CI/CD** 변경 | [infra/infra.md](infra/infra.md) |
| 리포 전체 구조·빠른 시작 변경 | [README.md](README.md) |
| 새 **기술/라이브러리/구조 패턴** 도입 | 그 모듈 문서에 반영 + **PR 본문에 왜 그걸 골랐는지 기록** |
| 새 **엔드포인트** 추가/변경 | Swagger 자동 생성 — 별도 문서 작업 없음. 도메인이 새로 생겼을 때만 지도 갱신 |
| 위에 없는 일반 코드 변경 | 문서 변경 불필요 |

**두 가지 하드 규칙:**
1. **문서 변경은 코드 변경과 같은 PR에 담는다.** 별도 "문서 정리" PR로 미루지 않는다.
2. **PR 본문에 "왜 그렇게 했는지"를 반드시 남긴다.** 대안을 왜 버렸는지, 무엇이 안 됐는지까지. 코드·diff에서 복원되지 않는 유일한 정보이고, PR 본문이 그 기록의 정본이다.

## 6. 가드레일 (하지 말 것)

```
- 담당 도메인 외 패키지 수정
- global 공통 구조·SecurityConfig·공통 응답/에러 구조 변경 (팀장 영역)
- COMMON ErrorCode 영역 수정 / Entity 연관관계 임의 변경 / API URI 임의 변경
- Entity를 API 응답으로 직접 반환 (Request/Response DTO 분리)
- 모바일 작업으로 backend/ 수정 (백엔드 불가침)
- 테스트 검증 없이 기능 완료 처리
- 위 5번 문서 동기화를 건너뛰고 코드만 머지
```

## 7. 더 읽기

읽는 순서 추천: [README.md](README.md)(띄우기) → 작업할 모듈의 `<모듈>.md` → 관련 PR 본문(`gh pr list`·`gh pr view <n>`)으로 과거 작업 맥락.
