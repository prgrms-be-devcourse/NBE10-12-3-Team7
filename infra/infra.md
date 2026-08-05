# infra — 배포 자원

컨테이너 배포 자원. **이 문서는 `infra/`의 진입점이자 지도다** — 절차는 각 폴더의 문서에 있다.

> 리포 전체는 [../README.md](../README.md).

## 세 지형

| 지형 | 무엇 | 어디 | 문서 |
|---|---|---|---|
| **dev** | 매일 개발 (MySQL만 Docker, 앱·프론트는 호스트) | 리포 루트 [`docker-compose.yml`](../docker-compose.yml) | [../README.md](../README.md) |
| **onprem** | **실제로 배포·운영하는 전체 스택** | [`onprem/`](onprem/) | [onprem/README.md](onprem/README.md) |
| **cloud** | AWS 관리형 미러 (IaC · 코드 전용, `apply` 안 함) | [`cloud-terraform/`](cloud-terraform/) | [cloud-terraform/README.md](cloud-terraform/README.md) |

`onprem/`이 실제 운영 스택이고, `cloud-terraform/`은 같은 아키텍처를 AWS 관리형 서비스로
**대응(mirror)**시킨 IaC다(포트폴리오 목적).

Dockerfile은 각 앱 폴더(`backend/`, `frontend/`)에 있다.

## 문서 지도

```
infra.md  (이 문서 — 지도)
├── onprem/README.md              스택 기동 · 프로파일 · 포트 · 관리자 계정 · 스키마
│   ├── CICD.md                   Jenkins 자동 배포
│   ├── monitoring/README.md      Prometheus · Loki · Grafana, 대시보드 3종
│   ├── perf/README.md            볼륨 테스트 — 데이터가 늘면 느려지는가
│   │   └── FINDINGS.md           성능 문제 대장 (F-01~F-05)
│   └── loadtest/README.md        부하 테스트 — 동시 요청이 늘면 버티는가 (k6)
│       ├── REMOTE-RUN.md         다른 머신에서 부하를 걸 때
│       └── results/              회차 기록 (조건 · 결론 · 버린 구간)
└── cloud-terraform/README.md     AWS 미러 (OpenTofu)
```

## 성능 검증

이 폴더의 큰 축 하나가 **성능 검증**이다. 축이 다른 두 종류를 따로 돌린다 —
**두 가지를 동시에 바꾸면 어느 쪽이 원인인지 알 수 없기 때문이다.**

| | [`perf/`](onprem/perf/README.md) | [`loadtest/`](onprem/loadtest/README.md) |
|---|---|---|
| 질문 | 데이터가 늘면 느려지는가 | 동시 요청이 늘면 버티는가 |
| 변수 | **데이터량** (부하 고정) | **동시성** (데이터 고정 10만) |
| 도구 | 직접 측정 스크립트 (curl) | k6 |
| 데이터 마커 | `[perf]` | `[load]` |

마커가 달라 **DB에 공존해도 서로를 건드리지 않는다.** 정리도 각자 마커로만 한다.

발견된 문제는 회차를 넘나들며 [`perf/FINDINGS.md`](onprem/perf/FINDINGS.md)에서 추적한다 —
발견부터 해결까지 한 곳에서 따라간다. 회차별 결론은 각 `results/*/summary.md`에 있고,
**측정 조건과 버린 구간을 함께 기록한다**(나중에 표만 보고 어떤 조건의 숫자인지 판별할 수 있어야 한다).

## 온프레미스 ↔ AWS 대응

> **이 표가 대응 관계의 정본이다.** 각 지형의 세부는 [onprem/README.md](onprem/README.md) ·
> [cloud-terraform/README.md](cloud-terraform/README.md)(파일별 코드 지도 포함)에 있다.

AWS 매니지드 서비스의 각 관심사를 OSS로 대응시켜, **같은 앱 이미지가 두 지형에서 동일하게
돌게** 했다.

| 관심사 | 클라우드(AWS) | 온프레미스(OSS) |
|---|---|---|
| 컨테이너 실행 | ECS Fargate | Docker Compose |
| DB · 캐시 | RDS · ElastiCache | MySQL · Redis 컨테이너 |
| AI | Ollama on EC2 | Ollama 컨테이너 (qwen3:1.7b) |
| 관측 | CloudWatch | Prometheus · Loki · Grafana |
| 외부 노출 | ALB · ACM · Route 53 | nginx · cloudflared |
| 이미지 레지스트리 | ECR | Zot |
| 오브젝트 스토리지 | S3 | RustFS |
| 배포 자동화 | GitHub Actions | Jenkins |

앱이 두 지형을 가르는 지점은 **한 곳뿐이다** — `S3Config`가 `file.storage.s3.endpoint`
**유무 하나로** 갈린다(있으면 정적 자격증명 + path-style, 없으면 AWS 기본 자격증명 체인).
프로파일은 양쪽 모두 `prod` 하나다.

## 빠른 참조

```bash
# 온프레미스 기동 — 절차 전체는 onprem/README.md
cd infra/onprem && cp .env.example .env
docker compose --env-file .env up -d zot && ./build-and-push.sh
docker compose --env-file .env up -d

# AWS 미러는 apply 하지 않고 검증만 한다
cd infra/cloud-terraform && tofu init && tofu validate && tofu plan
```

## 주의

- `.env`는 커밋하지 않는다(gitignore). `.env.example`이 필요한 키 목록이다.
- 배포 구성이 바뀌면 **해당 폴더의 문서**를 같은 PR에서 갱신한다.
