# MarketON e2e 테스트

실제로 뜬 백엔드에 HTTP 를 쏴서 **여러 계층이 함께 동작할 때만 드러나는 문제**를 잡는다.
Playwright 를 쓰지만 대부분 브라우저를 띄우지 않는다 — `request` fixture 로 API 만 때린다.

검증 대상이 백엔드 로직이기 때문이다. 프론트를 거치면 CSS 클래스 하나 바뀔 때마다 백엔드 테스트가
깨지고, 그러면 아무도 e2e 를 신뢰하지 않게 된다. 브라우저는 REST 로 못 찌르는 것에만 쓴다.

## 시작하기

```bash
npm install && npx playwright install chromium
```

```bash
npm run e2e:up
```

```bash
npm run smoke
```

`e2e:up` 이 MySQL·Redis·Mailpit·백엔드를 한 번에 띄우고 준비될 때까지 기다린다.
첫 실행은 백엔드 이미지 빌드 때문에 몇 분 걸리고, 이후에는 수십 초다.

`npm run smoke` 가 통과하면 환경은 정상이다. 바로 스펙을 쓰면 된다.

## 환경

포트가 **개발 환경과 어긋나 있다.** 개발 서버(8080·3000)를 켜둔 채로 e2e 를 돌릴 수 있다.

| 서비스 | e2e | (참고) dev |
| --- | --- | --- |
| 백엔드 | **18080** | 8080 |
| 프론트엔드 | **13000** | 3000 |
| MySQL | **3307** | 3306 |
| Redis | **6380** | 6379 |
| **Mailpit** | **8025** (웹 UI) / 1025 (SMTP) | — |

DB·Redis 는 tmpfs 라 컨테이너를 내리면 데이터가 사라진다. 정리 코드가 필요 없다.

발송된 메일은 <http://localhost:8025> 에서 눈으로 볼 수 있다.

## 명령

| 명령 | 하는 일 |
| --- | --- |
| `npm run e2e:up` | 환경 기동 + 준비 대기 |
| `npm run e2e:up:ui` | + 프론트엔드까지 (UI 스펙용) |
| `npm run e2e:down` | 정리 |
| `npm run e2e:reset` | 데이터 초기화 (down → up) |
| `npm run e2e:rebuild` | develop 갱신 후 백엔드 이미지 재빌드 |
| `npm run e2e:logs` | 컨테이너 로그 추적 |
| `npm run smoke` | **환경 점검** — 뭔가 이상하면 제일 먼저 |
| `npm run test:api` | API 스펙 전부 (평소엔 이것) |
| `npm run test:ui` | 브라우저 스펙 |
| `npx playwright test -g "재발급"` | 이름으로 골라서 |
| `npx playwright test --repeat-each=3` | flaky 확인 — **PR 전 필수** |
| `npx playwright test --ui` | **브라우저에서 스텝별로 보며 디버깅** |
| `npm run report` | 마지막 실행 리포트 |

## 뭔가 깨졌을 때

**`npm run smoke` 를 먼저 돌린다.** 판별이 30초면 끝난다.

| smoke | 내 스펙 | 원인 |
| --- | --- | --- |
| ✅ 통과 | ❌ 실패 | **스펙 또는 백엔드 로직 문제** — 스펙을 본다 |
| ❌ 실패 | ❌ 실패 | **환경 문제** — 스펙은 볼 필요 없다 |

환경 문제일 때:

```bash
npm run e2e:logs
```

백엔드 로그에 컴파일 에러가 보이면 `develop` 이 깨진 것이지 내 스펙 문제가 아니다.
(코틀린 마이그레이션이 진행 중이라 실제로 종종 일어난다. `git log develop` 확인)

## 디렉토리

```
e2e/
├── docker-compose.e2e.yml   환경 정의 (로컬·CI 공용)          ← 팀장
├── scripts/wait-for-env.mjs 준비 확인                        ← 팀장
├── playwright.config.ts     smoke / api / ui 프로젝트          ← 팀장
├── fixtures/                                                  ← 팀장
│   ├── test.ts              ★ 스펙은 여기서 test/expect 를 가져온다
│   └── auth.ts              가입·이메일인증·로그인·관리자
├── support/                                                   ← 팀장
│   ├── api.ts               응답 봉투 벗기기 + 에러 단언
│   ├── redis.ts             이메일 인증 코드 조회
│   └── unique.ts            겹치지 않는 식별자 생성
└── specs/
    ├── smoke/               환경 점검                          ← 팀장
    ├── api/                 ★ 팀원 작업 구역
    │   └── _TEMPLATE.spec.ts.txt   복사해서 시작
    └── ui/                  ★ 팀원 작업 구역 (브라우저 필수만)
```

**팀장 소유 영역을 고쳐야 할 것 같으면 먼저 말해달라.** 막으려는 게 아니라, 여기가 흔들리면
모든 스펙이 동시에 죽어서 원인 추적이 어려워지기 때문이다.

## 스펙 쓰는 법

1. [`specs/smoke/environment.spec.ts`](specs/smoke/environment.spec.ts) 를 읽는다 — 형식과 픽스처 사용의 실례
2. `_TEMPLATE.spec.ts.txt` 를 `<도메인>.spec.ts` 로 복사
3. 템플릿 맨 아래 체크리스트를 통과시키고 PR

### 픽스처

| 픽스처 | 정체 |
| --- | --- |
| `api` | 비로그인 백엔드 클라이언트. 401 / 인증 흐름 검증용 |
| `user` | 갓 가입·로그인한 회원. `user.api` 로 요청하면 토큰이 자동으로 붙는다 |
| `otherUser` | 또 다른 회원. "남의 리소스 → 403" 검증용 |
| `admin` | `AdminSeeder` 가 만든 관리자. 제재·대시보드 검증용 |

```ts
test('내가 만든 상품만 수정할 수 있다', async ({ user, otherUser }) => {
  const res = await user.api.post('/api/products', { data: { ... } });
  const mine = await unwrap<{ id: number }>(res, 201);

  const attempt = await otherUser.api.patch(`/api/products/${mine.id}`, { data: { ... } });
  await expectError(attempt, 403, 'PRODUCT_FORBIDDEN');
});
```

픽스처는 **참조한 테스트에서만** 생성된다. `otherUser` 를 안 쓰면 회원가입 비용도 안 든다.

> ⚠️ `admin` 은 **전역 공유 계정**이다. 로그인은 동시에 해도 되지만, 제재·삭제의 *대상*은
> 반드시 그 테스트가 새로 만든 회원이어야 한다.

### 응답 다루기

백엔드는 모든 응답을 봉투에 감싼다. `res.json().data` 를 직접 파헤치지 말고 헬퍼를 쓴다.

```ts
const product = await unwrap<ProductResponse>(res, 201);   // 성공 → data 만
await expectError(res, 409, 'DUPLICATE_EMAIL');            // 실패 → ErrorCode 까지 단언
```

상태코드가 다르면 **응답 본문을 통째로 붙여서** 실패시킨다.
`expected 200, received 400` 만 보고 원인을 찾아 헤매는 일이 없다.

## 규칙 5개

**1. 자기 데이터는 자기가 만든다.**
고정 ID·고정 이메일·시드 데이터에 의존하지 않는다. 식별자는 `uniqueEmail()` / `uniqueTitle()`.

**2. 전역 개수를 단언하지 않는다.**
```ts
expect(list.length).toBe(3);                      // ✗ 병렬에서 100% 깨짐
expect(list.map(p => p.id)).toContain(mine.id);   // ○
```

**3. 실행 순서를 가정하지 않는다.**
`fullyParallel: true` 다. 앞 테스트가 남긴 데이터에 기대는 순간 단독 실행에서 깨진다.

**4. 에러는 ErrorCode 까지 단언한다.**
상태코드만 보면 "400 이면 다 통과"가 되어 검증이 무의미해진다.

**5. flaky 는 방치하지 않는다.**
`retries: 0` 이다. 재시도로 덮지 않는다. 깜빡이기 시작하면 24시간 안에 고치거나
`test.skip` + 이슈 등록. 애매하게 두면 팀 전체가 빨간 불을 무시하기 시작하고,
그 순간 이 폴더는 죽은 자산이 된다.

## 무엇을 e2e 로 만드나

아래 네 축에 걸리지 않으면 e2e 대상이 아니다. 단위/슬라이스 테스트로 내려보낸다.

| 축 | 왜 e2e 여야 하나 |
| --- | --- |
| **돈** | 틀리면 복구가 안 된다 — 에스크로 상태 전이 |
| **권한** | 단위 테스트는 시큐리티 필터를 건너뛴다 — JWT 만료/재발급, 403, admin 접근 |
| **상태 전이** | 여러 테이블·서비스에 걸쳐 있다 — 상품 판매중→예약→완료, 신고→제재 |
| **동시성** | 단위 테스트로 재현이 불가능하다 — 경매 동시입찰 낙관락 |

## 알려진 제약

- **경매 입찰은 API 로 못 찌른다.** `AuctionBidController` 가 `@MessageMapping` 이라 REST
  엔드포인트가 없다. 조회·생성·마감은 `specs/api/`, 입찰은 `specs/ui/` 또는 STOMP 클라이언트로 나눈다.
- **`NEXT_PUBLIC_*` 는 빌드 타임에 박힌다.** 프론트 이미지의 `NEXT_PUBLIC_WS_ORIGIN` 을 런타임
  환경변수로 바꿔도 클라이언트 번들에는 반영되지 않는다. UI 실시간 스펙을 붙일 때 해결해야 한다.
- **CI 가 아직 없다.** 팀이 인프라 재설계 때문에 워크플로를 걷어낸 상태(#17)라, `develop → main`
  게이트는 인프라 재설계와 함께 붙인다. 지금은 로컬 수동 실행.
- **레이트 리밋을 e2e 에서는 껐다.** 워커 4개가 같은 IP 라 기본값(60req/10s)에 걸려 429 가 난다.
  `docker-compose.e2e.yml` 에서 `RATE_LIMIT_CAPACITY=100000` 주입. 429 를 검증하는 스펙을
  쓸 때는 별도 격리가 필요하다.
