# 마켓온

지역 기반 중고거래 서비스. **모노레포** — Spring Boot 백엔드 + Next.js 프론트 + Android 앱 + AI 에이전트 + 인프라.

> 처음이라면 이 문서로 **띄우고**, 각 모듈의 세부는 그 폴더의 문서를 본다.
> 문서는 **코드 옆에 둔다** — 모듈이 바뀌면 그 폴더의 문서를 같은 PR에서 갱신한다.

---

## 모듈 지도

| 폴더 | 무엇 | 문서 |
|---|---|---|
| `backend/` | Spring Boot 3.5 / Java 21 API 서버 | [backend/backend.md](backend/backend.md) |
| `frontend/` | Next.js 16 (App Router) 웹 클라이언트 | [frontend/frontend.md](frontend/frontend.md) |
| `mobile/` | Kotlin + Compose Android 앱 | [mobile/mobile.md](mobile/mobile.md) |
| `agent/` | Python + LangGraph AI 에이전트 서비스 | [agent/agent.md](agent/agent.md) |
| `infra/` | 배포 자원 (온프레미스 · AWS) | [infra/infra.md](infra/infra.md) |
| `e2e/` | Playwright e2e 테스트 (자체 격리 환경 포함) | [e2e/e2e.md](e2e/e2e.md) |

그 밖에:

```
docker-compose.yml   ★ dev 전용 (MySQL만 — 앱·프론트는 호스트에서 실행)
.env.example         dev용 환경변수 키 목록 (복사해서 .env)
AGENTS.md            AI 에이전트 작업 규칙 — 에이전트는 이걸 먼저 읽는다
```

## 기술 스택

| | |
|---|---|
| 백엔드 | Spring Boot 3.5, Java 21, Spring Security(JWT), JPA, MySQL 8, Flyway |
| 프론트 | Next.js 16(App Router), React 19, TypeScript, Tailwind |
| 모바일 | Kotlin, Jetpack Compose, Hilt, Retrofit |
| AI | Spring AI + Ollama (관리자 어시스턴트) · Python LangGraph (사용자 도메인) |
| 인프라 | Docker Compose, nginx, Prometheus·Loki·Grafana, Cloudflare Tunnel |

---

## 빠른 시작

> 전제: **Docker Desktop**, **JDK 21**. 모든 명령은 **리포 루트**에서. 먼저 `cp .env.example .env`.

### A. dev — 매일 개발 (권장, 빠른 루프)

MySQL만 Docker, 앱·프론트는 호스트에서 직접 실행:

```bash
docker compose up -d --wait
```

```bash
cd backend && ./gradlew bootRun
```

```bash
cd frontend && npm install && npm run dev
```

→ 브라우저 **http://localhost:3000** (`/api`는 :8080으로 프록시)

### B. 온프레미스 — 전부 Docker로 (운영 패리티·시연)

```bash
cd backend && ./gradlew clean build -x test && cd ..
```

```bash
cd infra/onprem && cp .env.example .env && docker compose --env-file .env up -d --build
```

→ 브라우저 **http://localhost** (nginx 현관 하나로 프론트·API 통합)

관측·외부노출 프로파일과 접속 지점은 [infra/infra.md](infra/infra.md).

### C. cloud — AWS 배포 (운영)

AWS **EC2 3대(앱 · DB · 모니터링) + ECR**. DB는 관리형 RDS가 아니라 EC2에 MySQL 컨테이너 자체 호스팅이다. 이미지는 GitHub Actions가 ECR로 push하고 EC2가 pull한다. 절차는 [infra/infra.md](infra/infra.md).

---

## 문서 규칙

문서는 **설명하는 대상 옆에** 둔다.

- 모듈 이야기 → 그 폴더의 `<모듈>.md`
- 리포 전체 이야기 → 이 `README.md`
- 에이전트 작업 규칙 → [`AGENTS.md`](AGENTS.md)
- 이번 작업을 왜·어떻게 했나 → **PR 본문** (머지된 PR이 작업 기록의 정본이다)

API 요청/응답 스키마의 정본은 **Swagger**(`http://localhost:8080/swagger-ui.html`)다 — 코드에서 자동 생성되므로 별도 문서로 옮겨 적지 않는다.

## 참고

- **CORS 설정 없음** — 어느 환경이든 브라우저는 단일 origin(dev=Next, 온프레미스·클라우드=nginx)만 호출하고 `/api`는 서버가 프록시한다.
- `.env`는 커밋하지 않는다(gitignore). `.env.example`이 필요한 키 목록.
