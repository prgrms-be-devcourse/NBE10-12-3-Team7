# infra — 배포 자원

컨테이너 배포 자원. **dev(매일 개발)** 는 리포 루트 [`docker-compose.yml`](../docker-compose.yml)(MySQL만)이 담당하고, **온프레미스 전체 스택**은 `onprem/`, **AWS 관리형 미러(IaC · 코드 전용)** 는 `cloud-terraform/` 이 담당한다.

> 이 문서는 `infra/`를 이해하는 진입점이다. 리포 전체는 [../README.md](../README.md).

## 구조

| 폴더 | 대상 |
|---|---|
| `onprem/` | **온프레미스 전체 스택(실운영)** — nginx · Next · Spring Boot · MySQL · Redis · Ollama 한 호스트, 이미지는 Zot에서 pull |
| `cloud-terraform/` | **AWS 관리형 미러 (OpenTofu · 코드 전용)** — ALB · ECS Fargate · RDS · ElastiCache · S3 · ECR · CloudWatch · Route53. `apply`·운영하지 않는다 |

`onprem/`이 실제로 배포·운영하는 스택이고, `cloud-terraform/`은 같은 아키텍처를 AWS 관리형 서비스로 **대응(mirror)**시킨 IaC다(포트폴리오 목적). 대응 관계·구성은 [cloud-terraform/README.md](cloud-terraform/README.md) 참고.

Dockerfile은 각 앱 폴더(`backend/`, `frontend/`)에 있다.

`S3Config`는 `file.storage.s3.endpoint` **유무 하나로** 두 지형을 가른다 — 값이 있으면 정적 자격증명 +
path-style(RustFS/MinIO 계열), 없으면 기존 AWS 기본 자격증명 체인. 프로파일은 양쪽 모두 `prod` 하나다.

## 기동 (온프레미스)

```bash
cd infra/onprem
cp .env.example .env                    # 값 채우기. .env 는 커밋 금지
docker compose --env-file .env up -d    # 이미지는 Zot에서 pull
```

> AWS 미러는 코드 전용이라 기동 대신 검증만 한다 — `cd infra/cloud-terraform && tofu validate && tofu plan`.

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

**배포(CD)는 온프레미스 Jenkins가 담당한다** (`cicd` 프로파일). GitHub Actions self-hosted
runner는 쓰지 않는다 — 이 리포는 public이라 포크 PR이 배포 호스트에서 임의 코드를 실행할 수
있다. Jenkins는 GitHub이 밀어넣는 게 아니라 우리가 당겨오는 방향이라 그 경로가 없다.

| 항목 | 값 |
|---|---|
| 접속 | `http://localhost:8080` (계정은 `.env`의 `JENKINS_ADMIN_*`) |
| 잡 | `dongnemarket-onprem-cd` — `jenkins/casc.yaml`이 생성한다. UI로 만들지 않는다 |
| 트리거 | SCM 폴링 `H/2 * * * *` — 웹훅이 아니라 호스트를 외부에 노출하지 않는다 |
| 추적 브랜치 | `.env`의 `JENKINS_TRACK_BRANCH` |
| 단계 | 빌드·Zot push → 배포 → 검증 (리포 루트 `Jenkinsfile`) |
| 배포 태그 | `build-{빌드번호}` 고정 — 지금 뜬 게 어느 빌드인지 컨테이너만 봐도 안다 |

설정은 `infra/onprem/jenkins/`(Dockerfile·plugins.txt·casc.yaml)가 소유한다. 컨테이너를
지우고 다시 만들어도 같은 상태로 복원된다.

파이프라인을 손댈 때 알아야 할 세 가지:

- **경로가 둘이다.** 빌드는 Jenkins 워크스페이스(방금 체크아웃한 커밋)에서, 배포는 호스트
  리포 경로(`HOST_REPO_PATH`)에서 돈다. 컨테이너 안에서 `docker compose up`을 해도 바인드
  마운트는 **호스트 기준**으로 해석되므로, 워크스페이스에서 띄우면 `nginx.conf` 등을 못 찾는다.
- **레지스트리 주소도 둘이다.** 태그에 박히는 `localhost:5000`은 호스트 도커 데몬이 보는
  주소다(push/pull을 수행하는 주체는 CLI가 아니라 데몬이다). 반면 Jenkins 컨테이너 안에서
  나가는 사전 확인 curl은 `zot:5000`이어야 한다 → `REGISTRY_PROBE`로 분리했다.
- **docker.sock 마운트 = 사실상 호스트 root 권한.** 개인 호스트에서 자기 리포만 빌드한다는
  전제의 트레이드오프다.

테스트(CI)는 아직 자동화되어 있지 않다:

| 항목 | 지금 |
|---|---|
| 테스트 게이트 | 각 담당자가 머지 전 `cd backend && ./gradlew test` 로 직접 확인 |
| 배포 | Jenkins가 추적 브랜치 push를 감지해 자동 배포 |

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

> ⚠️ **온프레미스는 현재 `SPRING_JPA_HIBERNATE_DDL_AUTO=update`로 우회 중이다.** Kotlin 전환으로
> 들어온 `auctions`·`escrows`·`manner_scores` 엔티티에 대응하는 마이그레이션(V7~)이 아직 없어
> `validate`로는 빈 DB에서 기동하지 못한다. 마이그레이션이 정리되면 `.env`의 이 키와
> compose의 해당 줄을 함께 지운다.

## 주의

- `.env`는 커밋하지 않는다(gitignore). `.env.example`이 필요한 키 목록이다.
- 온프레미스 관측은 `observability` 프로파일(Prometheus·Loki·Grafana)로 띄운다. AWS 미러의 관측은 CloudWatch(관리형).
- 배포 구성이 바뀌면 이 문서를 **같은 PR에서** 갱신한다.

---

## 온프레미스 모더나이제이션 — 무엇이 바뀌었나

AWS 매니지드 서비스의 각 관심사를 OSS로 대응시켜, 같은 앱 이미지가 두 지형에서 동일하게 돌게 했다.

| 관심사 | 클라우드(AWS) | 온프레미스(OSS) | 이전 |
|---|---|---|---|
| 컨테이너 실행 | ECS Fargate | Docker Compose | 동일 |
| DB · 캐시 | RDS · ElastiCache | MySQL · Redis 컨테이너 | 동일 |
| AI | Ollama on EC2 | Ollama 컨테이너 (qwen3:1.7b) | 동일 |
| 관측 | 자체호스팅 | Prometheus · Loki · Grafana | 동일 |
| 외부 노출 | ALB · ACM | nginx · cloudflared | 동일 |
| **이미지 레지스트리** | **ECR** | **Zot** | 배포 호스트에서 로컬 빌드 |
| **오브젝트 스토리지** | **S3** | **RustFS** | local 볼륨 마운트 |
| **배포 자동화** | GitHub Actions | **Jenkins** | 수동 `docker compose up` |

### 전체 기동 (fresh start)

```bash
cd infra/onprem
cp .env.example .env                                            # 값 채우기(.env는 커밋 금지)

docker compose --env-file .env up -d zot                        # ① 레지스트리 먼저
./build-and-push.sh                                             # ② 이미지 빌드 → Zot push
docker compose --env-file .env up -d                            # ③ 나머지(app/next는 Zot pull)

docker compose --env-file .env --profile cicd up -d --build jenkins   # CD
docker compose --env-file .env --profile observability up -d          # 관측
docker compose --env-file .env --profile edge up -d                   # 외부 임시 URL
```

`zot → build-and-push.sh → 나머지` 순서를 지켜야 한다. compose의 이미지 pull은 `depends_on`을
기다리지 않으므로(컨테이너 생성 *전* 단계) 레지스트리가 비어 있으면 app/next가 뜨지 못한다.

### 최초 1회만 필요한 것

RustFS 버킷 생성. 콘솔(`http://localhost:9001`)에서 만들거나:

```bash
docker run --rm --network dongnemarket-onprem_default \
  -e AWS_ACCESS_KEY_ID=$RUSTFS_ACCESS_KEY -e AWS_SECRET_ACCESS_KEY=$RUSTFS_SECRET_KEY \
  -e AWS_DEFAULT_REGION=us-east-1 amazon/aws-cli:latest \
  --endpoint-url http://rustfs:9000 s3 mb s3://marketon-images
```

### 포트

| 포트 | 서비스 | 비고 |
|---|---|---|
| 80 | nginx | 유일한 웹 진입점 |
| 3306 | mysql | |
| 5000 | zot | 레지스트리 |
| 8080 | jenkins | `cicd` 프로파일 |
| 9000 · 9001 | rustfs | S3 API · 콘솔 |
| 9090 · 3001 · 3100 | prometheus · grafana · loki | `observability` 프로파일 |

app(8080)·next(3000)은 `expose`만 하고 게시하지 않는다 — nginx를 통해서만 접근한다.

### 확인 명령

```bash
docker compose --env-file .env ps                  # 컨테이너 상태
curl -s localhost:5000/v2/_catalog                 # 레지스트리에 올라간 이미지
docker compose --env-file .env images              # 각 컨테이너가 실제로 쓰는 이미지·태그
```

> zot 컨테이너에는 healthcheck가 없다. 이미지가 zot 바이너리 하나만 담은 distroless라 셸·wget이
> 없어 컨테이너 안에서 실행할 수단이 없다. 가용성은 위 `_catalog` 응답으로 확인한다.
