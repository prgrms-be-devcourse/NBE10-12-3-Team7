# perf — 온프레미스 스택 성능 검증

**"적재 → 문제 발견 → 개선 → 재적재 → 확인"을 반복하는 작업 공간이다.** 한 번 재고 끝나는 게
아니라 회차를 쌓아 전후를 비교한다.

새로 붙었다면(사람이든 에이전트든) **여기부터 실행한다.** 지금 데이터가 얼마나 들어 있는지,
마지막 회차가 무엇이었는지, 다음에 뭘 해야 하는지를 한 화면에 보여준다.

```bash
cd infra/onprem/perf
./scenarios/status.sh
```

## 사이클

```
1. ./scenarios/status.sh          지금 상태 확인
2. FINDINGS.md                     고칠 문제 하나 고르기 — 한 번에 하나만
3. 개선 적용                        코드·설정 변경 + 커밋
4. ./scenarios/run-step.sh × N     같은 계단을 다시 밟기
5. ./scenarios/compare.sh          이전 회차와 비교표 생성
6. FINDINGS.md 갱신                해결 / 부분해결 / 실패 + 근거 숫자
7. summary.md 작성                 그 회차의 결론
```

**4번에서 조건이 같아야 한다** — 계단 구성·프로브·샘플 수. `run-step.sh` 가 그것을 고정하므로,
회차마다 달라지는 것은 **3번의 개선 내용 하나뿐**이어야 한다. 둘을 동시에 바꾸면 무엇이
효과였는지 영영 모른다.

## 회차 실행

```bash
# 회차 이름은 run-<번호>-<개선명>. 날짜는 폴더명이 아니라 summary.md 안에 적는다
./scenarios/run-step.sh 1  10000  run-2-region-index
./scenarios/run-step.sh 2  30000  run-2-region-index
./scenarios/run-step.sh 3 100000  run-2-region-index
./scenarios/run-step.sh 4 200000  run-2-region-index
./scenarios/run-step.sh 5 300000  run-2-region-index
./scenarios/run-step.sh 6 500000  run-2-region-index

./scenarios/compare.sh run-1-baseline run-2-region-index \
  > results/run-2-region-index/compare.md
```

`run-step.sh` 한 번이 **적재 → 안정화 30초 → 측정 → 대시보드 캡처**를 모두 한다.

> ⚠️ **스냅샷이 도는 동안 다른 트래픽을 흘리지 않는다.** 앱의 요청 제한이 10초당 60건이라
> 넘기면 429 가 측정된다. 거부 응답은 빨리 돌아오므로 **"빨라진 것처럼" 기록된다** —
> 실제로 한 번 겪었고, 그래서 `snapshot.sh` 가 HTTP 200 이 아니면 즉시 중단한다.

## 왜 볼륨 테스트인가

이 호스트는 Docker Desktop(macOS)이라 컨테이너 네트워크가 gvisor 유저스페이스를 거친다.
동시 요청을 올리면 앱이 아니라 네트워크 계층이 먼저 왜곡을 만들어 절대 수치를 믿을 수 없다.

볼륨 테스트는 부하를 낮게 유지한 채 데이터량만 바꾸므로 네트워크가 병목에 닿지 않는다.
관측되는 변화가 거의 순수하게 DB·쿼리 특성이다. **이 환경의 약점을 우회하는 설계다.**

그래서 이 폴더의 모든 수치는 **회차 간 상대 비교**에만 쓴다. 다른 환경과 비교하지 않는다.

## 구성

### 회차 불변 — 한 번 만들고 계속 쓴다

| 경로 | 역할 |
|---|---|
| `FINDINGS.md` | **문제 대장.** 발견부터 해결까지 회차를 관통해 따라간다 |
| `lib/common.sh` | 공통 헬퍼. `../.env` 로드, MySQL 실행, Prometheus 조회 |
| `dataset/00-precheck.sql` | 적재 전 전제 확인 |
| `dataset/10-products.sql` | 상품. `run-step.sh` 가 목표 건수에 맞춰 자동 조정 |
| `dataset/20-member-locations.sql` | 동네 설정. 없으면 지역 필터 경로를 재현할 수 없다 |
| `dataset/30-favorites.sql` | 찜 (테스트 계정에 3,000 집중) |
| `dataset/40-comments.sql` | 댓글 (인기 상품 1건에 3,000 집중) |
| `dataset/50-notifications.sql` | 알림 (테스트 계정 안읽음 3,000) |
| `dataset/60-reports.sql` | 신고 (테스트 계정 500) |
| `dataset/90-verify.sql` | 적재 후 건수·크기·분포 검증 |
| `dataset/99-cleanup.sql` | 마커가 붙은 볼륨 데이터만 삭제 |
| `scenarios/status.sh` | 콜드 스타트 — 지금 상태를 한 화면에 |
| `scenarios/run-step.sh` | 계단 하나 실행 (적재→안정화→측정→캡처) |
| `scenarios/snapshot.sh` | 측정만. 보통 `run-step.sh` 가 부른다 |
| `scenarios/compare.sh` | 두 회차 비교표 생성 |
| `results/TEMPLATE-manual.md` | 화면 클릭 실측 템플릿 |

### 회차 가변

| 경로 | 역할 |
|---|---|
| `results/run-1-baseline/` | 1회차. 기준선 — 개선 전 상태 |
| `results/run-N-<개선명>/` | 이후 회차 |

각 회차 폴더에 `step-N.json`(원시값) · `step-N-grafana.png`(그 시점 대시보드) ·
`summary.md`(결론) · `compare.md`(이전 회차 대비) · `manual.md`(화면 체감)가 쌓인다.

## 데이터 설계

**"한 계정에 몰아주기"가 핵심이다.** 나의 마켓온·신고내역·알림은 전부 `me` 기준이라,
전체 건수보다 **로그인한 한 사람의 건수**가 성능을 좌우한다. 100만 건이 골고루 퍼지면
아무 일도 안 일어난다.

**균등 분포를 피한다.** 지역은 상위 20곳에 60%, 카테고리는 2종에 55%, 조회수는 상위 1%에
집중, 등록 시각은 최근 30일에 50%. 고르게 뿌리면 인덱스 선택도가 비현실적으로 좋아져
결과가 낙관적으로 왜곡된다.

**볼륨 데이터에는 마커가 있다** — 상품 `title` 이 `[perf] `, 회원 `email` 이 `perf-`,
댓글·신고·알림 본문이 `[perf]`. 데모 시더가 만든 6건과 섞이지 않고, 정리도 마커로만 한다.

## 프로브 — 사람이 화면에서 실제로 부르는 것만

백엔드에 있어도 어떤 화면도 호출하지 않는 엔드포인트는 재지 않는다. 한때
`/api/categories/{id}/products` 를 재고 "느려졌다"고 판단했는데, 프론트를 뒤져보니 그 경로를
부르는 화면이 없었다. **아무도 겪지 않는 지연이었다.**

| 프로브 | 화면 동작 | API |
|---|---|---|
| `list_first` | 상품 목록 첫 진입 | `GET /api/products?size=30` |
| `list_region` | 동네 설정된 사용자의 목록 | `GET /api/products?size=30&regionCodes=..` |
| `list_deep` | 스크롤을 한참 내린 상태 | `GET /api/products?size=30&cursor=..` |
| `detail_hot` | 인기 상품 클릭 | `GET /api/products/{id}` (조회수 UPDATE 포함) |
| `comments` | 상세 진입 시 동시 호출 | `GET /api/products/{id}/comments` |
| `admin_products` | 관리자 상품 목록 | `GET /api/admin/products` (인증) |
| `admin_members` | 관리자 회원 목록 | `GET /api/admin/members` (인증) |
| `admin_dashboard` | 관리자 대시보드 집계 | `GET /api/admin/dashboard` (인증) |

**관리자도 사람이다.** 오히려 관리자 화면이 볼륨에 더 취약하다 — 컨트롤러 7개가 전부
페이징 없이 목록을 통째로 반환한다.

**재지 않는 것** — 카테고리 탭과 검색창은 서버를 부르지 않는다(클라이언트에서 배열을 거른다).
`/api/categories`(8건)와 `/api/regions`(5,338건)는 고정 크기라 볼륨과 무관하다.

**무엇을 넣을지 판별하는 기준** — 화면이 호출하면서 아래 중 하나에 걸리면 넣는다.
① 페이징이 없는가 ② 목록·검색을 반환하는가 ③ 조인이 많은가 ④ 집계를 하는가

## 실행 전제

- 온프레미스 스택 기동 — `docker compose --env-file .env up -d` (상위 폴더)
- 관측 스택 기동 — `--profile observability`. 측정값을 Prometheus 에서 읽고,
  대시보드 캡처를 `grafana-image-renderer` 가 만든다
- `../.env` 가 채워져 있을 것

## 규칙

1. **README 는 존재하는 파일만 나열한다.** 이전 `loadtest/` 는 스크립트 12개를 안내하는데
   실제로는 8개뿐이어서 신뢰를 잃었다
2. 실행 안내는 **macOS/Linux 기준**으로 적는다
3. 계정·비밀값은 **`../.env` 참조로만** 적는다. 값을 문서에 쓰지 않는다
4. **한 회차에 개선 하나만.** 둘을 동시에 바꾸면 무엇이 효과였는지 모른다
5. **개선 전 데이터를 지우지 않는다.** 같은 조건에서 재측정해야 비교가 성립한다
6. 측정이 실패하면 **기록하지 않는다.** 조용히 틀린 숫자가 표에 남는 것이 가장 위험하다

## dataset/ 은 Flyway 가 아니다

번호 접두사로 실행 순서를 표현하지만 마이그레이션이 아니다.
`backend/src/main/resources/db/migration/` 에 두면 운영 배포 때 실행되므로 **거기 두지 않는다.**

앱의 시더(`global/init` 의 마스터·부트스트랩·데모)와도 다르다. 그쪽은 앱이 기동할 때 넣는
데이터고, 여기는 실험용 볼륨이다. 그래서 이름도 `seed/` 가 아니라 `dataset/` 이다.

## 아직 없는 것

- `probe/` — k6 스크립트. 지금은 `snapshot.sh` 가 curl 로 재고 있어 동시성이 없다.
  볼륨 테스트에는 충분하지만, 동시 요청을 봐야 할 때 필요하다
- `manual.md` — 화면 클릭 체감. `TEMPLATE-manual.md` 를 회차 폴더에 복사해 채운다
