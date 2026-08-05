# monitoring — 관측 스택

> 상위: [../README.md](../README.md) · [../../infra.md](../../infra.md)

`observability` 프로파일로만 뜨는 컨테이너들이다. 기본 기동에는 포함되지 않는다.

```bash
cd infra/onprem
docker compose --env-file .env --profile observability up -d
```

→ Grafana **http://localhost:3001** (계정은 `.env` 의 `GRAFANA_ADMIN_*`, 기본 `admin`/`admin`)

## 구성

지표(Prometheus)와 로그(Loki)를 따로 모아 Grafana 한 곳에서 본다.

| 컨테이너 | 이미지 | 무엇을 모으나 |
|---|---|---|
| `prometheus` | prom/prometheus:v2.53.2 | 아래 5개 잡을 스크레이프 |
| `grafana` | grafana/grafana:11.1.4 | 대시보드 (호스트 **3001**) |
| `grafana-renderer` | grafana-image-renderer | 패널을 이미지로 렌더 (회차 스크린샷용) |
| `loki` | grafana/loki:3.1.1 | 로그 저장 (호스트 3100) |
| `promtail` | grafana/promtail:3.1.1 | **도커 소켓**에서 컨테이너 로그 수집 → Loki |
| `cadvisor` | cadvisor:v0.49.1 | 컨테이너별 CPU·메모리 |
| `node-exporter` | node-exporter:v1.8.2 | 호스트(리눅스 VM) 자원 |
| `mysqld-exporter` | mysqld-exporter:v0.15.1 | MySQL 내부 지표 |
| `nginx-exporter` | nginx-prometheus-exporter:1.3.0 | nginx 연결·요청 |

`promtail` 은 파일 경로가 아니라 **도커 서비스 디스커버리**로 붙는다(`docker_sd_configs`).
컨테이너가 새로 뜨면 5초 안에 자동으로 잡히고, `container`·`service`·`stream` 라벨이 붙는다.

## 스크레이프 간격 — 앱과 nginx만 5초다

`prometheus.yml` 의 global 은 15초인데 **두 잡만 5초로 좁혀 놓았다.** 이유가 성능 측정에 있다:

| 잡 | 간격 | 왜 |
|---|---|---|
| `dongnemarket-app` | **5s** | HikariCP `pending` 같은 게이지는 **순간값**이라 15초로는 짧게 튀는 포화 신호를 통째로 놓친다 |
| `nginx` | **5s** | 부하 계단이 30초 단위라 앱과 해상도를 맞춘다 |
| `prometheus` · `cadvisor` · `node` · `mysql` | 15s | 하드웨어·DB 는 이 해상도로 충분하다 |

커넥션 풀이 1번 병목 후보인데 그 신호를 놓치면 무릎의 원인을 특정할 수 없다.
5초로 좁히면 부하 계단 하나(2.5분)당 표본이 10개에서 **30개**로 늘어난다.

실제로 이 해상도 덕분에 `pending` 이 0 → 187 로 튀는 구간을 잡았다 —
근거는 [../loadtest/results/](../loadtest/results/) 의 회차 기록.

## 대시보드 3종

Grafana 의 **Load Test** 폴더에 자동 프로비저닝된다(`grafana/provisioning/dashboards/`).
UI 에서 만들지 않는다 — 컨테이너를 지우고 다시 만들어도 같은 상태로 복원된다.

### `onprem-overview` — 온프레미스 종합 · 앱 · 컨테이너 · DB · 하드웨어

평소 스택 상태와 부하 회차를 **한 화면에서** 위에서 아래로 훑는 용도다. 5개 행:

| 행 | 질문 | 주요 패널 |
|---|---|---|
| ① 앱 | 서비스가 건강한가 | 요청률(엔드포인트별) · 응답시간 · 에러율(5xx) · **JVM 힙 + DB 커넥션 풀(대기 포함)** |
| ② 컨테이너 | 자원을 얼마나 쓰나 | 합계만 (Docker Desktop 제약) |
| ③ 데이터베이스 | DB가 병목인가 | 커넥션 · QPS · 슬로우 쿼리 · **행 잠금 대기** · 버퍼풀 · **버퍼풀 히트율** |
| ④ 하드웨어 | 측정이 유효한가 | **VM CPU** · 메모리 · 디스크 I/O · 부하 |
| ⑤ 부하 | 밖에서 본 것 (k6) | 걸린/버린 부하 · **무부하 대비 배수** · 클라이언트가 본 응답 · 실패율 |

> ①행의 커넥션 풀 패널에 **`pending`(대기 큐)** 을 넣은 것이 부하 2회차에서 값을 했다.
> `active` 만 보면 풀이 꽉 차도 "상한에 붙어 있다"로만 보이고, 실제로 밀리는지는
> 대기 큐에만 나타난다.

### `traffic` — 트래픽 부하 · 웹 진입 계층 (nginx · 네트워크)

`onprem-overview` 가 앱·DB 를 본다면 이쪽은 **그 앞단**을 본다. 벽이 앱인지 nginx 인지
네트워크인지 가를 때 쓴다.

| 행 | 주요 패널 |
|---|---|
| ① 부하 (k6) | 걸린/버린 부하 · 무부하 대비 배수 · 클라이언트가 본 응답·실패율 |
| ② nginx | 연결 상태(활성·읽는 중·쓰는 중·대기) · 초당 요청·연결 수락 · **수락했지만 처리 못 한 연결** |
| ③ 네트워크 | VM 네트워크 처리량 · **패킷 드롭·에러** |
| ④ 하드웨어 | VM CPU · 메모리 · 부하 |

③행은 노트북에서 부하를 걸 때 **벽이 Wi-Fi 인지 앱인지** 가르려고 추가했다.

### `loadtest` — 부하테스트 모니터링 (k6 + App)

회차를 돌리는 동안 실시간으로 보는 화면. 클라이언트(k6)가 본 것과 서버가 본 것을 **나란히** 둔다.

- k6: API별 응답시간 p95/p99(`name` 라벨별) · 처리량(RPS)/VUs · 실패율
- 앱: **HikariCP 커넥션(active/pending/idle)** · JVM 힙 · 엔드포인트별 요청률 · 엔드포인트별 평균 지연 · CPU(프로세스/시스템)

같은 순간을 양쪽에서 보기 때문에 **"앱은 30ms 인데 클라이언트는 840ms"** 같은 격차가 바로 드러난다.
그 격차가 곧 네트워크 비용이다.

## k6 지표가 들어오는 경로

k6 는 스크레이프 대상이 아니라 **remote-write 로 밀어 넣는다.**

```
k6 컨테이너 ──(remote-write)──▶ prometheus:9090/api/v1/write ──▶ Grafana
```

- k6 는 시간 지표를 **초 단위**로 보낸다 (요약 22.4ms ↔ Prometheus `0.022`)
- 트렌드 분위수는 기본이 **회차 누적값**이라 계단별로 못 읽는다 →
  네이티브 히스토그램을 켜야 구간별 분위수가 나온다
- **경로별 배수는 k6 요약 JSON 에만 있고 Prometheus 로 넘어오지 않는다** —
  k6 가 강제 종료되면 못 읽는다

자세한 것은 [../loadtest/README.md](../loadtest/README.md).

## 주의

- **로그 수집에 `docker.sock` 을 읽기 전용으로 마운트한다.** 컨테이너 목록과 로그를 보기 위한
  것이지만, 소켓 접근은 그 자체로 권한이 크다. 개인 호스트 전제의 트레이드오프다.
- Loki 는 파일시스템 저장(`/loki`)에 단일 인스턴스 구성이다. 보존·복제 설정이 없으므로
  **회차 기록을 로그에 의존하지 않는다** — 회차의 정본은 `results/` 폴더다.
- ②행이 합계만 보여주는 것은 Docker Desktop 의 VM 구조 때문이다. 컨테이너별 분해가 필요하면
  `docker stats` 를 따로 샘플링한다(회차 스크립트가 `raw/docker-stats.csv` 로 남긴다).
