# onprem — 온프레미스 전체 스택

> 상위: [../infra.md](../infra.md) · 리포 전체: [../../README.md](../../README.md)

**이 프로젝트가 실제로 배포·운영하는 스택이다.** 맥 호스트 한 대에서 Docker Compose로
nginx · Next · Spring Boot · MySQL · Redis · Ollama · RustFS · Zot 를 띄운다.
앱 이미지는 로컬 빌드가 아니라 **Zot 레지스트리에서 pull** 한다.

옆의 [`cloud-terraform/`](../cloud-terraform/README.md) 은 같은 아키텍처를 AWS 관리형
서비스로 대응시킨 IaC이고, `apply` 하지 않는다.

## 문서 지도

| 문서 | 내용 |
|---|---|
| **이 문서** | 기동 · 프로파일 · 포트 · 최초 1회 설정 · 관리자 계정 · 스키마 |
| [CICD.md](CICD.md) | Jenkins 자동 배포 — 잡 구성, 파이프라인 함정 3가지 |
| [monitoring/README.md](monitoring/README.md) | Prometheus · Loki · Grafana, 대시보드 3종 |
| [perf/README.md](perf/README.md) | **볼륨 테스트** — 데이터가 늘면 느려지는가 |
| [perf/FINDINGS.md](perf/FINDINGS.md) | **성능 문제 대장** — 발견부터 해결까지 추적 (F-01~F-05) |
| [loadtest/README.md](loadtest/README.md) | **부하 테스트** — 동시 요청이 늘면 버티는가 (k6) |

> 성능 검증이 이 폴더의 큰 축이다. `perf/` 와 `loadtest/` 는 **질문이 다르다** —
> 앞은 데이터량 축, 뒤는 동시성 축이다. 자세한 것은 각 README.

## 기동

### 전체 기동 (fresh start)

```bash
cd infra/onprem
cp .env.example .env                                            # 값 채우기(.env는 커밋 금지)

docker compose --env-file .env up -d zot                        # ① 레지스트리 먼저
./build-and-push.sh                                             # ② 이미지 빌드 → Zot push
docker compose --env-file .env up -d                            # ③ 나머지(app/next는 Zot pull)
```

> **`zot → build-and-push.sh → 나머지` 순서를 지켜야 한다.** compose의 이미지 pull은
> `depends_on`을 기다리지 않는다(컨테이너 생성 *전* 단계). 레지스트리가 비어 있으면
> app/next가 뜨지 못한다.

JAR 선행 빌드는 불필요하다 — `backend/Dockerfile`이 멀티스테이지(`temurin:21-jdk`)로
컨테이너 안에서 빌드한다.

### 프로파일

기본 기동에 포함되지 않는 것들이다. 필요한 것만 얹는다.

```bash
docker compose --env-file .env --profile cicd up -d --build jenkins   # 자동 배포
docker compose --env-file .env --profile observability up -d          # 관측
docker compose --env-file .env --profile edge up -d                   # 외부 임시 URL
```

| 프로파일 | 무엇이 뜨나 | 문서 |
|---|---|---|
| `cicd` | Jenkins | [CICD.md](CICD.md) |
| `observability` | Prometheus · Grafana · Loki · promtail · exporter 4종 · cadvisor | [monitoring/README.md](monitoring/README.md) |
| `edge` | cloudflared (Cloudflare Quick Tunnel) | — |

### 종료

```bash
cd infra/onprem && docker compose --profile observability --profile edge down
```

데이터 볼륨은 유지된다.

## 접속 지점

| 대상 | 주소 |
|---|---|
| 프론트 / API | http://localhost · http://localhost/api/... |
| Grafana | http://localhost:3001 |
| Prometheus | http://localhost:9090 |
| Jenkins | http://localhost:8080 |
| RustFS 콘솔 | http://localhost:9001 |
| 외부 임시 URL | `docker logs dongne-cloudflared 2>&1 \| grep trycloudflare` |

### 포트

| 포트 | 서비스 | 비고 |
|---|---|---|
| 80 | nginx | **유일한 웹 진입점** |
| 3306 | mysql | |
| 5000 | zot | 레지스트리 |
| 8080 | jenkins | `cicd` 프로파일 |
| 9000 · 9001 | rustfs | S3 API · 콘솔 |
| 9090 · 3001 · 3100 | prometheus · grafana · loki | `observability` 프로파일 |

app(8080)·next(3000)은 `expose`만 하고 게시하지 않는다 — **nginx를 통해서만 접근한다.**
Grafana가 호스트 3001인 것은 3000이 next와 충돌하기 때문이다.

## 확인 명령

```bash
docker compose --env-file .env ps                  # 컨테이너 상태
curl -s localhost:5000/v2/_catalog                 # 레지스트리에 올라간 이미지
docker compose --env-file .env images              # 각 컨테이너가 실제로 쓰는 이미지·태그
```

`_catalog` 는 `{"repositories":["dongnemarket-app","dongnemarket-next"]}` 를 반환해야 한다.

> zot 컨테이너에는 healthcheck가 없다. 이미지가 zot 바이너리 하나만 담은 distroless라
> 셸·wget이 없어 컨테이너 안에서 실행할 수단이 없다. 가용성은 위 `_catalog` 응답으로 확인한다.

## 최초 1회만 필요한 것

### RustFS 버킷 생성

콘솔(`http://localhost:9001`)에서 만들거나:

```bash
docker run --rm --network dongnemarket-onprem_default \
  -e AWS_ACCESS_KEY_ID=$RUSTFS_ACCESS_KEY -e AWS_SECRET_ACCESS_KEY=$RUSTFS_SECRET_KEY \
  -e AWS_DEFAULT_REGION=us-east-1 amazon/aws-cli:latest \
  --endpoint-url http://rustfs:9000 s3 mb s3://marketon-images
```

### 관리자 계정

**운영(prod)에는 관리자 시더가 없다.** `AdminSeeder`는 `@Profile("dev")` + `APP_SEED_ADMIN`
스위치라 prod 프로파일인 온프레미스·AWS에서는 빈 자체가 만들어지지 않는다.

> 예전에는 `@Profile("!test")`였다. "test만 아니면 전부"라서 **운영에서도 관리자가 자동
> 생성**됐고, 비밀번호가 소스에 평문으로 있었다. 리포가 public이라 배포된 서버에 누구나
> 관리자로 들어올 수 있었다. 시더를 dev로 좁히고 비밀번호를 env로 뺀 것이 그 대응이다.

배포 후 1회, 아래 순서로 만든다. **비밀번호 해시를 손으로 만들지 않는 것**이 요점이다 —
앱의 인코더를 그대로 쓰므로 알고리즘·스트렝스가 어긋날 일이 없다.

```bash
# 1) 평범한 회원으로 가입한다 (웹 UI 또는 /api/auth/signup — 이메일 인증까지 정상 통과)
# 2) 그 계정만 관리자로 승격한다
docker compose exec mysql mysql -udongne -p dongne_market \
  -e "UPDATE members SET role='ROLE_ADMIN' WHERE email='<가입한 이메일>';"
```

승격 후 확인:

```bash
docker compose exec mysql mysql -udongne -p dongne_market \
  -e "SELECT email, role, status FROM members WHERE role='ROLE_ADMIN';"
```

`role`은 `@Enumerated(EnumType.STRING)`이라 문자열 `ROLE_ADMIN`이 그대로 들어간다.
부하테스트에서 관리자 화면을 재려면(`PROBE_ADMIN=true`) 이 계정의 비밀번호를
`ADMIN_PASSWORD` 환경변수로 넘긴다 — 스크립트에 기본값은 없다.

## 스키마

운영 스키마는 **Flyway 마이그레이션**으로 관리하고 `ddl-auto: validate`로 검증한다.
마이그레이션 파일은 `backend/src/main/resources/db/migration/`.

> ⚠️ **온프레미스는 현재 `SPRING_JPA_HIBERNATE_DDL_AUTO=update`로 우회 중이다.**
> Kotlin 전환으로 들어온 `auctions`·`escrows`·`manner_scores` 엔티티에 대응하는
> 마이그레이션(V7~)이 아직 없어 `validate`로는 빈 DB에서 기동하지 못한다.
> 마이그레이션이 정리되면 `.env`의 이 키와 compose의 해당 줄을 함께 지운다.

## 주의

- `.env`는 커밋하지 않는다(gitignore). `.env.example`이 필요한 키 목록이다.
- 배포 구성이 바뀌면 이 문서를 **같은 PR에서** 갱신한다.
- `S3Config`는 `file.storage.s3.endpoint` **유무 하나로** 두 지형을 가른다 — 값이 있으면
  정적 자격증명 + path-style(RustFS/MinIO 계열), 없으면 AWS 기본 자격증명 체인.
  프로파일은 양쪽 모두 `prod` 하나다.
