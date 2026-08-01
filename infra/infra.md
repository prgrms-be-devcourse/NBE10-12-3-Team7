# infra — 배포 자원

컨테이너 배포 자원. **dev(매일 개발)** 는 리포 루트 [`docker-compose.yml`](../docker-compose.yml)(MySQL만)이 담당하고, **온프레미스 전체 스택**은 `onprem/`, **AWS 관리형 미러(IaC · 코드 전용)** 는 `cloud-terraform/` 이 담당한다.

> 이 문서는 `infra/`를 이해하는 진입점이다. 리포 전체는 [../README.md](../README.md).

## 구조

| 폴더 | 대상 |
|---|---|
| `onprem/` | **온프레미스 전체 스택(실운영)** — nginx · Next · Spring Boot · MySQL · Redis · Ollama 한 호스트, 이미지 로컬 빌드 |
| `cloud-terraform/` | **AWS 관리형 미러 (OpenTofu · 코드 전용)** — ALB · ECS Fargate · RDS · ElastiCache · S3 · ECR · CloudWatch · Route53. `apply`·운영하지 않는다 |

`onprem/`이 실제로 배포·운영하는 스택이고, `cloud-terraform/`은 같은 아키텍처를 AWS 관리형 서비스로 **대응(mirror)**시킨 IaC다(포트폴리오 목적). 대응 관계·구성은 [cloud-terraform/README.md](cloud-terraform/README.md) 참고.

Dockerfile은 각 앱 폴더(`backend/`, `frontend/`)에 있다.

## 기동 (온프레미스)

```bash
cd infra/onprem
cp .env.example .env                    # 값 채우기. .env 는 커밋 금지
docker compose --env-file .env up -d    # 이미지 로컬 빌드
```

> AWS 미러는 코드 전용이라 기동 대신 검증만 한다 — `cd infra/cloud-terraform && tofu validate && tofu plan`.

### 온프레미스 — 프로파일

```bash
cd backend && ./gradlew clean build -x test && cd ..   # 앱 이미지용 JAR 선행 빌드(필수)

cd infra/onprem
docker compose --env-file .env up -d --build                                          # nginx+next+app+mysql
docker compose --env-file .env --profile observability up -d --build                  # +관측
docker compose --env-file .env --profile observability --profile edge up -d --build   # +외부노출(퀵터널)
```

접속 지점:

| 대상 | 주소 |
|---|---|
| 프론트 / API | http://localhost · http://localhost/api/... |
| Grafana | http://localhost:3001 |
| Prometheus | http://localhost:9090 |
| 외부 임시 URL | `docker logs dongne-cloudflared 2>&1 \| grep trycloudflare` |

종료 (데이터 볼륨은 유지):

```bash
cd infra/onprem && docker compose --profile observability --profile edge down
```

## CI/CD

> 🚧 **현재 없음.** 인프라 구조를 대폭 변경할 예정이라 파이프라인을 걷어냈다
> (`.github/workflows/ci.yml`, `cd-app.yml` 제거). Kotlin 마이그레이션을 마친 뒤 인프라와 함께 재설계한다.

그동안의 대체 수단:

| 항목 | 지금 |
|---|---|
| 테스트 게이트 | 각 담당자가 머지 전 `cd backend && ./gradlew test` 로 직접 확인 |
| 배포 | 자동 배포 없음. develop 머지가 배포로 이어지지 않는다 → 필요 시 수동 |

> ⚠️ Java↔Kotlin 혼재 기간에는 도메인 간 컴파일 영향이 있다(예: `Member` 엔티티는 7개 도메인에서
> 34회 참조). 자기 브랜치 테스트로는 안 잡히므로 **develop 머지 직후 전체 테스트를 한 번 더** 돌린다.

### 재구축 시 반영할 항목

- 테스트 게이트 (`./gradlew test` on PR)
- Kotlin 마이그레이션 진척 카운터 (`.java`/`.kt` 집계 → Step Summary)
- ktlint 게이트 승격 — `backend/build.gradle` 의 `ignoreFailures = false`
- AWS 인증은 **OIDC**(`secrets.AWS_ROLE_ARN`), 배포 대상 `secrets.EC2_APP_HOST`, 접속 키 `secrets.EC2_SSH_KEY`
- ⚠️ 리포를 이전하면 **IAM 역할의 신뢰 정책에 새 리포 경로를 허용**해야 한다.
  안 하면 모든 배포가 OIDC 인증에서 실패한다.

## 스키마

운영 스키마는 **Flyway 마이그레이션**으로 관리하고 `ddl-auto: validate`로 검증한다. 마이그레이션 파일은 `backend/src/main/resources/db/migration/`.

## 주의

- `.env`는 커밋하지 않는다(gitignore). `.env.example`이 필요한 키 목록이다.
- 온프레미스 관측은 `observability` 프로파일(Prometheus·Loki·Grafana)로 띄운다. AWS 미러의 관측은 CloudWatch(관리형).
- 배포 구성이 바뀌면 이 문서를 **같은 PR에서** 갱신한다.
