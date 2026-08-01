# infra — 배포 자원

컨테이너 배포 자원. **dev(매일 개발)** 는 리포 루트 [`docker-compose.yml`](../docker-compose.yml)(MySQL만)이 담당하고, **전체 스택 배포**는 배포 지형별로 이 폴더에 나뉜다.

> 이 문서는 `infra/`를 이해하는 진입점이다. 리포 전체는 [../README.md](../README.md).

## 구조

| 폴더 | 대상 |
|---|---|
| `onprem/` | **온프레미스 전체 스택** — nginx · Next · Spring Boot · MySQL 한 호스트, 이미지는 Zot에서 pull |
| `cloud/app/` | 앱 EC2 — nginx · Next.js · Spring Boot (ECR pull, DB 연결) |
| `cloud/db/` | DB EC2 — MySQL 8 컨테이너 (관리형 RDS 대신 EC2 직접 호스팅) |
| `cloud/monitoring/` | 모니터링 EC2 — Prometheus · Loki · Grafana |

`onprem`과 `cloud`는 **같은 앱 이미지·같은 nginx 라우팅**을 쓴다. 차이는 프로파일이 아니라 env와 이미지 출처뿐이다.

| | onprem | cloud |
|---|---|---|
| 이미지 | Zot(사설 레지스트리)로 push → 스택이 pull | ECR로 push → EC2가 pull (자동화는 재설계 중, 아래 CI/CD 참고) |
| 파일 저장 | `s3` + RustFS(`FILE_STORAGE_S3_ENDPOINT` 지정) | `s3` + AWS S3(endpoint 미지정) |

`S3Config`는 `file.storage.s3.endpoint` **유무 하나로** 두 지형을 가른다 — 값이 있으면 정적 자격증명 +
path-style(RustFS/MinIO 계열), 없으면 기존 AWS 기본 자격증명 체인. 프로파일은 양쪽 모두 `prod` 하나다.

Dockerfile은 각 앱 폴더(`backend/`, `frontend/`)에 있으며 **지형 무관 공용**이다.

## 기동

```bash
# 각 지형 폴더(onprem/ 또는 cloud/<역할>/)에서:
cp .env.example .env                    # 값 채우기. .env 는 커밋 금지
docker compose --env-file .env up -d    # onprem: Zot pull / cloud: ECR pull
```

### 온프레미스 — 프로파일

```bash
# JAR 선행 빌드는 불필요하다 — backend/Dockerfile이 멀티스테이지(temurin:21-jdk)로 컨테이너 안에서 빌드한다.
cd infra/onprem
docker compose --env-file .env up -d zot                                     # ① 레지스트리(ECR 대응) 먼저
./build-and-push.sh                                                          # ② app/next 빌드 → Zot push
docker compose --env-file .env up -d                                         # ③ 나머지(app/next는 Zot에서 pull)
docker compose --env-file .env --profile observability up -d                 # +관측
docker compose --env-file .env --profile observability --profile edge up -d  # +외부노출(퀵터널)

# 레지스트리 확인
curl -s localhost:5000/v2/_catalog     # {"repositories":["dongnemarket-app","dongnemarket-next"]}
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
- 관측 스택은 **클라우드 전용**이다. 로컬 관측은 온프레미스의 `observability` 프로파일로 띄운다.
- 배포 구성이 바뀌면 이 문서를 **같은 PR에서** 갱신한다.
