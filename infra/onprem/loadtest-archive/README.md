# k6 부하테스트 (온프레미스)

동네마켓 온프레미스 스택(`infra/onprem`, nginx 80포트 진입)을 대상으로 한 k6 부하 스크립트.

## 대상 엔드포인트 선정 근거
- `read-load.js` — 읽기 혼합. **`GET /api/notifications/unread-count`** 를 핵심 타깃으로 가중.
  헤더 배지라 최고 빈도로 폴링되고, 내부적으로 `getMyRooms`(채팅방 3쿼리)를 재사용해 호출당 비용도 큰 "고빈도 × 숨은 조인" 엔드포인트다.
  함께 알림 목록 / 댓글 목록(비로그인 공개) / 내 찜 목록을 섞는다.
- `write-load.js` — 쓰기. 댓글 작성(새 row + 남의 상품이면 AFTER_COMMIT 알림 발행).

## 실행 가능한 스크립트 목록
도메인별로 **한 파일 = 한 스크립트**. 각 파일 상단 주석에 대상 API·필요 env·실행 예시가 있다.

| 도메인 | 파일 | 대상 API | 토큰 |
|---|---|---|---|
| 알림/찜/댓글 | `read-load.js` | unread-count · 알림목록 · 댓글목록 · 내 찜 | 필요 |
| 댓글 | `write-load.js` | POST 댓글 작성 (+알림 발행) | 필요 |
| 회원 | `member-load.js` | GET /api/members/me | 필요(TOKEN) |
| AI | `admin-ai-chat.js` | POST /api/admin/ai/chat (LLM, 별도 범주) | 자동 로그인 |
| 상품 | `product-search.js` | 검색 LIKE 풀스캔 | 불필요 |
| 상품 | `category-products.js` | 무페이징 카테고리 목록 | 불필요 |
| 상품 | `product-list-cursor.js` | 커서 깊은 페이지 | 불필요 |
| 상품 | `product-detail-hotrow.js` | 조회수 락 경합 (p99 핵심) | 불필요 |
| 신고 | `report-duplicate.js` | 동시 중복 신고 방지 (정합성) | 필요 |
| 신고 | `report-list-n1.js` | 신고 목록 N+1 | 필요 |
| 신고 | `evidence-image-upload.js` | 증빙 멀티파트 업로드 | 필요 |
| 신고 | `cancel-race.js` + `admin-status-race.js` | 취소 vs 상태변경 경합 (정합성, 2터미널 동시) | 각각 필요 |

> 설계 배경 문서는 `product-load.plan.md`, `report-load.plan.md` 로 보존(실행 파일 아님). 위 개별 `.js` 로 분리됨.

## 사전 준비
1. 온프레미스 스택 기동 (`infra/onprem`에서):
   ```powershell
   docker compose --env-file .env --profile observability --profile edge up -d --build
   ```
2. **시드 데이터**: 로그인 가능한 계정 1개 + 접근 가능한 상품 1개.
   회원가입 API(`POST /api/auth/signup`)나 Swagger(`http://localhost/swagger-ui.html`)로 만들어 둔다.
   - 댓글 작성 부하는 남의 상품에 달면 알림 이벤트 경로까지 타므로, 부하 계정과 다른 사람 소유의 상품 id를 쓰면 더 현실적이다.
3. **k6 설치** (Windows):
   ```powershell
   winget install k6   # 또는: choco install k6
   ```

## 실행
```powershell
# 읽기 부하
k6 run -e BASE_URL=http://localhost -e EMAIL=시드계정 -e PASSWORD=비번 -e PRODUCT_ID=상품id read-load.js

# 쓰기 부하
k6 run -e BASE_URL=http://localhost -e EMAIL=시드계정 -e PASSWORD=비번 -e PRODUCT_ID=상품id write-load.js
```
- 다른 머신(같은 LAN)에서 서버로 부하를 걸 때는 `BASE_URL=http://<서버IP>` 로.
- 부하 생성기(k6)와 서버는 **다른 머신**에 두는 것이 이상적(같은 머신이면 CPU를 나눠 써 결과가 왜곡됨).

## 결과 보는 법
- **터미널 요약**: 실행이 끝나면 k6가 `http_req_duration`, `http_req_failed`, RPS(`http_reqs`)를 출력. 임계값(threshold) 통과 여부도 표시.
- **퍼센타일 표기 (공통 기준)**: 모든 스크립트 `options` 에 `summaryTrendStats: ['avg','min','med','p(90)','p(95)','p(99)','max']` 를 넣어 **p90/p95/p99 를 항상 동일 포맷으로** 출력한다. p99 는 k6 기본 요약에 안 나오므로 이 설정이 있어야 보인다. 파일을 안 고치고 일회성으로 강제하려면:
  ```powershell
  k6 run --summary-trend-stats="avg,med,p(90),p(95),p(99),max" read-load.js
  ```
- **엔드포인트별 p95·p99 판정**: 각 API 의 목표값을 `THRESHOLD_P95_MS`/`THRESHOLD_P99_MS`(템플릿형) 또는 태그 threshold `'http_req_duration{name:xxx}': ['p(95)<..','p(99)<..']`(read/write) 로 걸어, 목표 대비 달성값과 통과여부가 함께 찍히게 했다. 값은 baseline 측정 후 조정하는 **잠정 기준점**이다.
- **개선 전후 비교**: 부하 프로파일(STAGES/VU/시드/BASE_URL)을 동일하게 고정하고, 실행마다 `--summary-export=results/<script>_<before|after>.json` 로 저장하거나 아래 Prometheus 연동에 `--tag testid=` 를 다르게 줘 Grafana 에서 오버레이한다.
- ⚠️ **공통 기준 전파**: 아래 모든 실행 스크립트에 p99 threshold + `summaryTrendStats` 를 반영했다. 다른 팀원이 새 스크립트를 추가할 때도 동일하게 넣어야 결과 포맷이 일치해 비교가 성립한다.
- **서버측 지표(Grafana)**: observability 프로파일이 떠 있으면 `http://localhost:3001` (admin/admin) 에서 앱·MySQL 지표 확인.
- **k6 → Prometheus 연동(선택)**: 실시간 k6 지표를 Grafana에서 보려면 Prometheus에 remote-write 리시버를 켜고
  (`--web.enable-remote-write-receiver`) k6를 다음처럼 실행:
  ```powershell
  k6 run -o experimental-prometheus-rw --tag testid=read1 read-load.js
  # 기본 K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write
  ```

## 튜닝 포인트
- 부하 강도는 각 스크립트 `options.scenarios.*.stages` 의 `target`(동시 VU 수)과 `duration` 으로 조절.
- 병목을 찾을 땐 VU를 계단식으로 올리며 p95 가 꺾이는 지점(무릎)을 관찰.

## 정리
- `write-load.js` 는 실행할수록 comment row 가 쌓인다. 테스트 후 정리:
  ```sql
  DELETE FROM comment WHERE content LIKE '부하테스트 댓글%';
  ```
