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

## 측정 위치가 둘이다 — 회차마다 어느 쪽인지 적는다

같은 스크립트를 두 위치에서 돌린다. **조건이 다르므로 절대 수치를 섞어 비교하면 안 된다.**

| | 맥 로컬 (오버레이) | 노트북 (LAN) |
|---|---|---|
| `BASE_URL` | `http://nginx:80` (컨테이너 내부) | `http://192.168.219.112` |
| 실행 | `-f docker-compose.yml -f docker-compose.onprem.yml` | 오버레이 없이 |
| 무부하 실측 | 23.9 ms | 25.6 ms |
| 무엇을 재나 | **앱 자체의 상한**(낙관적) — Wi-Fi 를 타지 않는다 | **사용자 실측** — 실제로 겪는 지연 |

**두 회차의 차이가 곧 네트워크 비용이다.** 그래서 둘 다 돌리는 것이고, 어느 쪽인지 기록하지
않으면 그 차이를 해석할 수 없다.

절대 수치가 달라도 판정은 성립한다 — `setup()` 이 회차마다 그 경로의 무부하를 직접 재고
**무부하 대비 배수**로 판정하기 때문이다(`scripts/lib/config.js` 의 `latencyRatio`).

### 회차 기록에 반드시 남길 조건

숫자만 남기면 나중에 그 숫자가 무엇의 결과인지 판별할 수 없다. 표마다 아래를 같이 적는다.

| 항목 | 왜 필요한가 |
|---|---|
| 측정 위치 | 위 표의 둘 중 어느 쪽인가 |
| 앱 이미지 빌드 시점 | 코드가 달라지면 비교가 성립하지 않는다 |
| 요청 제한값 | 풀기 전인가 후인가 (`RATE_LIMIT_CAPACITY`) |
| 데이터 | `[load]` 상품 몇 건인가 |
| 배경 부하 | 젠킨스·관측 스택 상주 여부 |
| VM 자원 | Docker Desktop 할당 CPU·메모리, MySQL buffer pool |

## 맥에서 돌릴 때 (스택과 같은 호스트)

노트북 없이 맥 한 대로 돌릴 수 있다. k6 를 온프렘 스택의 네트워크에 붙여 `nginx` 를 컨테이너
이름으로 직접 부른다 — 맥의 Wi-Fi 를 타지 않아 **앱 자체의 상한**에 가깝게 잰다.

```bash
cd infra/onprem/loadtest
cp .env.example .env          # BASE_URL 은 오버레이가 덮지만 base compose 가 필수로 요구한다

# 오버레이를 얹으면 BASE_URL 이 http://nginx:80 으로 바뀐다
docker compose -f docker-compose.yml -f docker-compose.onprem.yml run --rm k6 run /scripts/s00-ratelimit.js

# 짧은 동작 확인(35초). 기록에는 남기지 않는다
SMOKE=true docker compose -f docker-compose.yml -f docker-compose.onprem.yml run --rm k6 run /scripts/s01-arrival.js
```

> ⚠️ 부하 생성기와 서버가 같은 CPU 를 나눠 쓴다. Grafana 의 VM CPU 가 90% 를 넘은 구간은
> 앱이 아니라 호스트 포화를 잰 것이므로 폐기한다.

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

# 0단계 — 요청 제한이 도는지 먼저 확인
docker compose run --rm k6 run /scripts/s00-ratelimit.js

# 1단계 — 사람이 몰린다 (맥에서 제한을 푼 뒤)
docker compose run --rm k6 run /scripts/s01-arrival.js

# Grafana 에서 실시간으로 보려면 (.env 에 K6_PROMETHEUS_RW_SERVER_URL 설정 후)
docker compose run --rm k6 run -o experimental-prometheus-rw /scripts/s01-arrival.js
```

## 시나리오 — 단계적으로 쌓는다

단순한 것부터 시작해 하나씩 얹는다. 각 단계에서 **무엇이 달라졌는지** 명확해야 원인을 짚을 수 있다.

| 단계 | 파일 | 무엇 | 추가되는 것 |
|---|---|---|---|
| **0** | `s00-ratelimit.js` | 요청 제한 확인 | — (측정이 아니라 전제) |
| **1** | `s01-arrival.js` | **사람이 몰린다** | 비로그인 목록 진입만 |
| 2 | *(예정)* | 로그인 사용자 | 토큰 + locations·favorites (API 2→4개) |
| 3 | *(예정)* | 머물러 있는 사람 | 알림 배지 60초 폴링 (배경 부하) |
| 4 | *(예정)* | 동선 | 스크롤 · 상세 · 채팅 |

관리자 화면은 **대상에서 뺐다.** 2~3명이 쓰는 화면이라 동시성 문제가 아니다.
`admin/products` 가 6.5초인 것은 페이징이 없어서지 사람이 몰려서가 아니며, 그건
`../perf/FINDINGS.md` 의 F-02 로 따로 추적한다.

### 0단계 — 요청 제한이 실제로 막는가

앱에는 IP 당 슬라이딩 윈도우 제한이 있다(기본 초당 6건, `RateLimitFilter`). 노트북 한 대는
IP 하나라 VU 5명만 돼도 이 벽에 부딪힌다 — 모르고 올리면 앱이 아니라 제한기를 측정하게 된다.

초당 20건을 30초간 건다. **70% 안팎이 429 로 잘리면 정상**이다(6/20 = 30% 만 통과).
이게 확인되면 이후 단계에서 제한을 푸는 근거가 된다.

### 1단계 — 사람이 몰린다

비로그인 사용자가 상품 목록 화면을 연다. 그게 전부다. 스크롤도 클릭도 없다.

```
반복 1회 = 화면 진입 1회
  http.batch([ GET /api/categories, GET /api/products?size=30 ])   ← 프론트의 Promise.all 그대로
  + think time 3~5초
```

**VU 계단 5 → 20 → 50 → 100 → 200 → 400**, 각 단계 2분 유지 + 30초 램프업 (총 약 15분).

400 까지 잡은 근거 — 이 앱의 이론상 한계가 **동시 770명**이다(DB 커넥션 10개 ÷ 목록 응답
13ms → 초당 770건, think time 4초 기준). 경험적으로 이론값의 30~50% 에서 꺾이므로
**무릎은 200~400 사이**로 예상한다. 병목은 **HikariCP 커넥션 10개**일 것으로 본다.

무릎을 찾으면 그 VU 로 **10분 유지 테스트**를 따로 돌린다 — 30초는 버티는데 5분 뒤 무너지는
경우가 흔하다(커넥션 누수·GC 누적).

```bash
SMOKE=true  docker compose run --rm k6 run /scripts/s01-arrival.js   # 동작 확인, 35초
            docker compose run --rm k6 run /scripts/s01-arrival.js   # 계단 전체, 약 15분
SOAK_VU=200 docker compose run --rm k6 run /scripts/s01-arrival.js   # 무릎에서 10분 유지
```

### 판정 기준

| 항목 | 기준 |
|---|---|
| 응답시간 | p95 < **무부하 대비 3배** (목록 13ms → 39ms) |
| 실패율 | < 1% |
| 429 | 만나면 **즉시 중단** — 그 상태의 기록은 남기지 않는다 |

절대값이 아니라 **무부하 대비 상대값**으로 본다. 이 환경의 절대 수치는 다른 곳과 비교할 수 없다.

## 구조 — 시나리오가 늘어나는 것을 전제로 잡았다

```
scripts/
├── lib/
│   ├── config.js    BASE_URL · 기준선 · 임계값 · 429 중단 규약
│   ├── screens.js   화면 = API 묶음   ← 이 폴더의 핵심
│   └── stages.js    VU 계단 · think time
├── s00-ratelimit.js
└── s01-arrival.js
```

**`screens.js` 가 핵심이다.** 시나리오는 화면을 조합할 뿐 API 를 직접 부르지 않는다.

- **VU 1명 = 사람 1명**이 되게 하려면 세는 단위가 요청이 아니라 화면이어야 한다.
  목록을 열면 API 가 동시에 여러 개 나간다 — 요청으로 세면 "동시 100명"이 몇 명인지 모른다
- 프론트의 호출 묶음을 그대로 옮겨둔다. 화면이 바뀌면 **여기만 고치면** 모든 시나리오가 따라온다
- 나중에 동선 시나리오(4단계)를 짤 때 **화면을 이어붙이기만** 하면 된다

**`stages.js` 에 계단을 모은 이유** — 시나리오마다 흩어 적으면 하나만 고쳐도 조건이 어긋나고,
나중에 "이 회차는 계단이 달랐다"를 알아채지 못한다. 회차 비교가 성립하려면 계단이 같아야 한다.

## 설계에서 신경 쓴 것

**대상을 하드코딩하지 않는다.** 노트북에는 DB 가 없다. 상품 id·지역 코드가 필요한 시나리오는
목록 API 응답에서 뽑아 쓴다(`screens.js` 의 `pickFromList`) — 데이터를 다시 적재해 id 가
바뀌어도 스크립트를 안 고쳐도 된다. (`perf` 에서 id 를 하드코딩했다가 404 를 맞은 적이 있다.)

**429 를 만나면 즉시 중단한다.** 거부 응답은 본문이 없어 아주 빨리 돌아온다 — 그대로 기록하면
**"빨라졌다"로 잘못 남는다.** 볼륨 테스트에서 실제로 당했고, 그래서 `expectOk()` 가 429 를
만나면 안내와 함께 테스트를 끝낸다. `s00-ratelimit` 만 예외다(429 를 기대하고 재는 시나리오라서).

**VU 마다 `sleep()` 을 넣는다.** 사람은 요청을 쉬지 않고 던지지 않는다. 이걸 빼면 VU 하나가
초당 수백 건을 쏘아 **VU 수가 "동시 사용자"라는 의미를 잃는다.**

## 알아야 할 제약

**Wi-Fi 면 결과를 믿기 어렵다.** 무선은 매 패킷마다 지연이 2~20ms 씩 흔들려 **p95·p99 가 앱이
아니라 무선 상태를 반영**한다. 같은 AP 에 붙은 다른 기기가 영상을 보면 측정값이 바뀌어
재현성도 깨진다. **유선 연결을 강하게 권한다.** 유선이 안 되면 같은 시나리오를 **맥에서도 한 번**
돌려(네트워크 없는 값) 둘을 비교하면 무선이 얼마를 먹는지 드러난다. 다만 맥에서는 k6 와 앱이
CPU 를 나눠 쓰므로 VU 100 까지만 돌린다.

**절대 수치는 다른 환경과 비교할 수 없다.** 맥의 Docker Desktop 은 컨테이너 네트워크가 gvisor
유저스페이스를 거친다. 같은 조건에서 잰 **회차 간 상대 비교**로만 쓴다.

**IP 가 바뀐다.** 공유기가 DHCP 로 주소를 바꿀 수 있고, 유선으로 전환하면 인터페이스가 달라져
IP 도 달라진다. 실행 전에 `ipconfig getifaddr en0` 로 확인한다.

**k6 도 자원을 쓴다.** VU 400 이면 노트북이 먼저 힘들 수 있다. 결과를 볼 때 k6 쪽 CPU 도 함께 본다.

## 무엇이 먼저 무너질지

측정 전 예상이다. 맞는지 확인하는 것도 이 테스트의 목적이다.

| 순서 | 벽 | 값 |
|---|---|---|
| 1 | 요청 제한 | IP 당 초당 6건 — s00 으로 확인 후 푼다 |
| 2 | **DB 커넥션 풀** | **10개** (HikariCP 기본값, 미지정) ← 여기가 유력하다 |
| 3 | Tomcat 스레드 | 200 (기본값) |

커넥션 풀이 병목이면 `perf` 와 같은 서사가 된다 — 무릎을 찾고, 늘리고, 재측정.

## 구성

| 경로 | 실행 위치 | 역할 |
|---|---|---|
| `docker-compose.yml` | 노트북 | k6 서비스 하나 |
| `.env.example` | 노트북 | BASE_URL · Prometheus 주소 |
| `scripts/lib/config.js` | 노트북 | 기준선 · 임계값 · 429 중단 |
| `scripts/lib/screens.js` | 노트북 | 화면별 API 묶음 |
| `scripts/lib/stages.js` | 노트북 | VU 계단 · think time |
| `scripts/s00-ratelimit.js` | 노트북 | 0단계 — 요청 제한 확인 |
| `scripts/s01-arrival.js` | 노트북 | 1단계 — 사람이 몰린다 |
| `lib/common.sh` | 맥 | DB 헬퍼 |
| `dataset/10-seed.sql` | 맥 | 상품 10만 (`[load]` 마커) |
| `dataset/90-verify.sql` | 맥 | 적재 검증 |
| `dataset/99-cleanup.sql` | 맥 | `[load]` 만 삭제 |
| `setup/load-data.sh` | 맥 | 적재 + 검증 |
| `setup/unload-data.sh` | 맥 | 정리 |
| `results/` | — | 회차별 결과 (k6 요약 JSON) |

## 왜 볼륨을 10만으로 고정하나

부하테스트는 **동시성**을 재는 것이라 볼륨이 변수가 되면 안 된다. `perf` 의 볼륨 테스트에서
버퍼풀 무릎이 **10만→20만 구간**이라는 것을 확인했으므로, 그 아래인 10만에 고정한다.
여기서 VU 를 올렸을 때 느려지면 **볼륨이 아니라 동시성 때문**이라고 말할 수 있다.
