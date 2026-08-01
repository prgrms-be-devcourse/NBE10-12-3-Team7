# loadtest — k6 부하테스트

**동시 사용자가 늘어날 때 어디까지 버티나**를 잰다. 옆의 `perf/`(볼륨 테스트)와는 질문이 다르다.

| | `perf/` | `loadtest/` |
|---|---|---|
| 질문 | 데이터가 늘면 느려지는가 | 동시 요청이 늘면 버티는가 |
| 변수 | 데이터량 (부하 고정) | 동시성 (데이터 고정 10만) |
| 실행 위치 | 맥 (curl) | **다른 노트북** (도커 k6) |
| 데이터 마커 | `[perf]` | **`[load]`** |

마커가 달라 **DB 에 공존해도 서로를 건드리지 않는다.** 정리도 각자 마커로만 한다.

## 두 대가 나눠 맡는다

```
맥북 (앱·DB·관측)                    노트북 (부하 생성)
  ├ setup/load-data.sh   데이터 적재    └ docker compose run k6 ...
  ├ 스택·Grafana·Prometheus
  └ setup/unload-data.sh 정리
```

부하 생성기와 서버를 같은 머신에 두면 CPU 를 나눠 써 결과가 왜곡된다. 그래서 나눈다.

## 맥에서 (준비)

```bash
cd infra/onprem/loadtest

# 1. 데이터 적재 (상품 10만 · 회원 300 · 인기 상품 댓글 500)
./setup/load-data.sh

# 2. LAN IP 확인 — 노트북에 알려줄 값
ipconfig getifaddr en0

# 3. 요청 제한 풀기 (s01 로 동작 확인한 뒤에)
cd .. && RATE_LIMIT_CAPACITY=100000 docker compose --env-file .env up -d app

# 끝나면 정리
./setup/unload-data.sh
```

## 노트북에서 (부하)

**도커만 있으면 된다.** k6 도 Node 도 설치할 필요가 없다.

```bash
git clone https://github.com/prgrms-be-devcourse/NBE10-12-3-Team7.git
cd NBE10-12-3-Team7/infra/onprem/loadtest
cp .env.example .env          # BASE_URL 을 맥의 LAN IP 로

# 시나리오 실행
docker compose run --rm k6 run /scripts/s01-ratelimit.js
docker compose run --rm k6 run /scripts/s00-network-floor.js
docker compose run --rm k6 run /scripts/s02-browse.js

# Grafana 에서 실시간으로 보려면 (.env 에 K6_PROMETHEUS_RW_SERVER_URL 설정 후)
docker compose run --rm k6 run -o experimental-prometheus-rw /scripts/s02-browse.js
```

## 시나리오

**순서대로 돌린다.** 앞 시나리오가 뒤의 전제를 만든다.

### s01-ratelimit — 요청 제한이 실제로 막는가

**제한을 푸는 게 아니라 확인하는 단계다.** 앱에는 IP 당 슬라이딩 윈도우 제한이 있다
(기본 10초당 60건 = 초당 6건, `RateLimitFilter`). 노트북 한 대는 IP 하나라 이 벽에 먼저 부딪힌다.

초당 20건을 30초간 건다. **70% 안팎이 429 로 잘리면 정상**이다(6/20 = 30% 만 통과).
방어 장치가 의도대로 동작한다는 것 자체가 기록할 가치가 있고, 이후 시나리오에서 제한을
올리는 근거가 된다.

> 실측: 총 601건 중 421건(70.0%) 차단 — 설정값과 일치

### s00-network-floor — 바닥값

**s02 보다 먼저 돌린다.** 부하를 LAN 으로 걸면 응답시간에 네트워크 구간이 섞인다. 이 바닥값을
모르면 "VU 를 올렸더니 느려졌다"가 앱 때문인지 네트워크 때문인지 갈라낼 수 없다.

가장 가벼운 경로(`/api/categories` — 8건 고정, 0.2 KB)에 같은 VU 계단을 건다. 순수한
네트워크만은 아니고 **네트워크 + nginx + 앱 + 가벼운 DB 조회**의 합이다. 데이터량과 무관한
경로라, 여기서 나오는 값이 이 환경의 "더 이상 못 내려가는 바닥"이다.

k6 가 구간을 나눠주므로 함께 본다 — 부하를 올렸을 때 `http_req_waiting`(TTFB)이 늘면 앱,
`http_req_connecting`·`http_req_receiving` 이 늘면 네트워크 쪽이다.

### s02-browse — 주 시나리오

실제 사용자 행동 비율로 섞는다. 하나만 때리면 실제 부하 모양이 아니다.

| 비중 | 동작 | API |
|---|---|---|
| 40% | 목록 진입 (동네 필터) | `/api/products?size=30&regionCodes=..` |
| 30% | 스크롤 | `/api/products?size=30&cursor=..` |
| 20% | 상세 클릭 | `/api/products/{id}` |
| 10% | 댓글 열람 | `/api/products/{id}/comments` |

VU 를 **5 → 10 → 20 → 40 → 80** 으로 올리며 각 단계를 2분 유지한다. 램프업 중의 값은
과도기라 판정에 쓰지 않는다.

**응답이 작은 것만 넣었다.** 관리자 상품 목록은 수백 MB 라, 여러 VU 가 동시에 받으면 측정하는
것이 앱이 아니라 네트워크 대역폭이 된다.

## 설계에서 신경 쓴 것

**대상을 하드코딩하지 않는다.** 노트북에는 DB 가 없다. 상품 id·지역 코드를 `setup()` 단계에서
목록 API 응답에서 뽑아 쓴다 — 데이터를 다시 적재해 id 가 바뀌어도 스크립트를 안 고쳐도 된다.
(`perf` 에서 id 를 하드코딩했다가 404 를 맞은 적이 있다.)

**429 를 만나면 즉시 중단한다.** 거부 응답은 본문이 없어 아주 빨리 돌아온다 — 그대로 기록하면
**"빨라졌다"로 잘못 남는다.** 볼륨 테스트에서 실제로 당했고, 그래서 `expectOk()` 가 429 를
만나면 안내와 함께 테스트를 끝낸다. `s01` 만 예외다(429 를 기대하고 재는 시나리오라서).

**VU 마다 `sleep()` 을 넣는다.** 사람은 요청을 쉬지 않고 던지지 않는다. 이걸 빼면 VU 하나가
초당 수백 건을 쏘아 **VU 수가 "동시 사용자"라는 의미를 잃는다.**

## 알아야 할 제약

**Wi-Fi 면 결과를 믿기 어렵다.** 무선은 매 패킷마다 지연이 2~20ms 씩 흔들려 **p95·p99 가 앱이
아니라 무선 상태를 반영**한다. 같은 AP 에 붙은 다른 기기가 영상을 보면 측정값이 바뀌어
재현성도 깨진다. **유선 연결을 강하게 권한다.** 유선이 안 되면 `s00` 바닥값을 반드시 먼저
재고, 앱 수치에서 그만큼을 감안해 읽는다.

**절대 수치는 다른 환경과 비교할 수 없다.** 맥의 Docker Desktop 은 컨테이너 네트워크가 gvisor
유저스페이스를 거친다. 같은 조건에서 잰 **회차 간 상대 비교**로만 쓴다.

**IP 가 바뀐다.** 공유기가 DHCP 로 주소를 바꿀 수 있고, 유선으로 전환하면 인터페이스가 달라져
IP 도 달라진다. 실행 전에 `ipconfig getifaddr en0` 로 확인한다.

**k6 도 자원을 쓴다.** VU 80 이면 노트북이 먼저 힘들 수 있다. 결과를 볼 때 k6 쪽 CPU 도 함께 본다.

## 무엇이 먼저 무너질지

측정 전 예상이다. 맞는지 확인하는 것도 이 테스트의 목적이다.

| 순서 | 벽 | 값 |
|---|---|---|
| 1 | 요청 제한 | IP 당 초당 6건 — s01 로 확인 후 푼다 |
| 2 | **DB 커넥션 풀** | **10개** (HikariCP 기본값, 미지정) ← 여기가 유력하다 |
| 3 | Tomcat 스레드 | 200 (기본값) |

커넥션 풀이 병목이면 `perf` 와 같은 서사가 된다 — 무릎을 찾고, 늘리고, 재측정.

## 구성

| 경로 | 실행 위치 | 역할 |
|---|---|---|
| `docker-compose.yml` | 노트북 | k6 서비스 하나 |
| `.env.example` | 노트북 | BASE_URL · Prometheus 주소 |
| `scripts/lib/config.js` | 노트북 | 대상 탐색 · 임계값 · 429 중단 |
| `scripts/s00-network-floor.js` | 노트북 | 바닥값 |
| `scripts/s01-ratelimit.js` | 노트북 | 요청 제한 확인 |
| `scripts/s02-browse.js` | 노트북 | 워크로드 믹스 |
| `lib/common.sh` | 맥 | DB 헬퍼 |
| `dataset/10-seed.sql` | 맥 | 상품 10만 (`[load]` 마커) |
| `dataset/90-verify.sql` | 맥 | 적재 검증 |
| `dataset/99-cleanup.sql` | 맥 | `[load]` 만 삭제 |
| `setup/load-data.sh` | 맥 | 적재 + 검증 |
| `setup/unload-data.sh` | 맥 | 정리 |
| `results/` | — | 회차별 결과 |

## 왜 볼륨을 10만으로 고정하나

부하테스트는 **동시성**을 재는 것이라 볼륨이 변수가 되면 안 된다. `perf` 의 볼륨 테스트에서
버퍼풀 무릎이 **10만→20만 구간**이라는 것을 확인했으므로, 그 아래인 10만에 고정한다.
여기서 VU 를 올렸을 때 느려지면 **볼륨이 아니라 동시성 때문**이라고 말할 수 있다.
