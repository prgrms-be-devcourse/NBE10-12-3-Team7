# frontend — Next.js 웹 클라이언트

마켓온 웹 프론트엔드. **Next.js 16 (App Router) / React 19 / TypeScript**.

> 이 문서는 `frontend/`를 이해하는 진입점이다. 리포 전체는 [../README.md](../README.md), 에이전트 작업 규칙은 [../AGENTS.md](../AGENTS.md).

## 스택

| | |
|---|---|
| 프레임워크 | Next.js 16 (App Router), React 19 |
| 언어 | TypeScript |
| 스타일 | CSS Modules (`*.module.css`) + Tailwind |
| 실시간 | STOMP over WebSocket (`@stomp/stompjs`) — 채팅 수신·경매 입찰·헤더 알림 배지 |
| 배포 | Dockerfile 포함 — onprem/cloud 공용 이미지 |

## 폴더 구조

```
frontend/src/
├── app/          라우트 (App Router). 폴더 = URL 경로
├── components/   라우트 간 공용 컴포넌트
├── lib/          API 클라이언트·유틸 (순수 함수 위주)
└── data/         정적 데이터 (약관·정책 문서 등)
```

## 라우트 지도

| 경로 | 화면 |
|---|---|
| `/` | 홈 — 상품 목록 |
| `/login` · `/signup` | 로그인 · 회원가입(약관 동의 포함) |
| `/find-password` · `/password-reset` | 비밀번호 찾기 · 재설정 |
| `/oauth/kakao/callback` · `/oauth/google/callback` | 소셜 로그인 콜백 |
| `/products` | 상품 목록·상세·등록·수정 |
| `/auctions` | 실시간 경매(딜) — 목록·등록·상세(STOMP 입찰) |
| `/chat` | 1:1 채팅 |
| `/escrow` | 안심결제(에스크로) |
| `/my-profile` | 내 정보 · 동네 설정 |
| `/my-reports` | 내 신고 내역 |
| `/report` | 신고 접수 |
| `/(my-marketon)` | 내 마켓온 — 라우트 그룹 (`/favorites` · `/my-products`) |
| `/admin` | 관리자 |

## 실행

```bash
npm install
npm run dev        # :3000 — /api 는 :8080 으로 프록시
```

백엔드가 `:8080`에 떠 있어야 API가 동작한다. 기동 방법은 [../backend/backend.md](../backend/backend.md).

거래 법률 상담 위젯은 `/agent`가 `:8000`(별도 FastAPI 서비스)으로 프록시된다 — 그 위젯을 쓰려면 [../agent/agent.md](../agent/agent.md)도 띄운다.

## 검증

```bash
npx tsc --noEmit   # 타입 체크
npx eslint .       # 린트
```

- 화면 작업은 **브라우저로 실제 확인**하는 것이 최종 검증이다 — 데스크톱·모바일 폭 양쪽.
- 순수 함수(`lib/`)는 단위 테스트 대상, 화면은 브라우저 검증 대상.

## 주의

- **단일 origin 원칙** — 브라우저는 단일 origin(dev=Next, 배포=nginx)만 호출하고 `/api`는 프록시된다. API 호출 시 절대 URL을 쓰지 않는다.
- 지역은 문자열이 아니라 **`regionCode`(계층형 지역 마스터)** 기준이다.
- 새 라우트를 추가하면 이 문서의 라우트 지도를 **같은 PR에서** 갱신한다.
