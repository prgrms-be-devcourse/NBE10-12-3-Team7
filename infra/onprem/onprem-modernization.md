# 온프레미스 모더나이제이션 — 맥북 호스트 구축 핸드오프

> **대상**: 맥북(Apple Silicon)에서 작업하는 Claude 에이전트
> **목적**: 남는 맥북을 24시간 온프레미스 호스트로 세팅. AWS 매니지드 각 관심사를 OSS로 대응(mirror)시켜 "온프레미스·클라우드 둘 다 운영" 포트폴리오 서사를 완성한다.
> **방침**: 마감 임박 → **일단 동작하는 세팅을 끝까지 올리고**, 학습·정리는 이후. 각 스텝 끝에서 검증 로그를 남기고 보고한다.

---

## 0. 전제 · 환경

| 항목 | 값 |
|---|---|
| 호스트 | 맥북 Apple Silicon (arm64) |
| 런타임 | **Docker Desktop** (설치·구동 확인됨). colima 아님 |
| 리포 | `github.com/prgrms-be-devcourse/NBE10-12-3-Team7` |
| 베이스 브랜치 | **`feat/onprem-modernization`** ← Ollama 통합된 onprem compose가 포함된 브랜치 |
| 볼륨 | fresh start(빈 볼륨) 전제 |

⚠️ **먼저 확인**: 이 문서가 가리키는 onprem compose에는 이미 **Ollama(qwen3:1.7b) 통합**이 들어가 있어야 한다. 베이스 브랜치를 clone/pull 한 뒤 `grep -c ollama infra/onprem/docker-compose.yml`이 **0이 아니어야** 정상. 0이면 잘못된 브랜치이니 중단하고 보고.

## 대응 매핑 (무엇을 왜 올리나)

| 관심사 | 클라우드(AWS) | 온프레미스(OSS) | 현재 리포 |
|---|---|---|---|
| 컨테이너 실행 | ECS Fargate | Docker Compose | ✅ |
| DB / 캐시 | RDS / ElastiCache | MySQL · Redis 컨테이너 | ✅ |
| AI | Ollama on EC2 | Ollama 컨테이너(qwen3:1.7b) | ✅ |
| 관측 | 자체호스팅 | Prometheus·Loki·Grafana (`observability` 프로파일) | ✅ |
| 외부노출/LB | ALB+ACM | nginx + cloudflared (`edge` 프로파일) | ✅ |
| **이미지 레지스트리** | **ECR** | **Zot** | ⬜ 이번 작업 |
| **오브젝트 스토리지** | **S3** | **RustFS** | ⬜ 이번 작업 |
| **CI/CD 러너** | GitHub-hosted | **GHA self-hosted runner** | ⬜ 이번 작업 |

## 확정된 방향 (팀장 승인)

1. **범위**: Zot + RustFS + self-hosted runner **3종 전부**.
2. **Zot 통합 깊이**: onprem compose가 app/next를 **로컬 빌드가 아니라 Zot에서 pull**하도록 전환(ECR 방식 그대로 재현).
3. **백엔드 S3Config 코드 변경**: 이 에이전트가 **함께 처리**(§Step 2).

---

# Step 0 — Baseline 기동 (현재 있는 것부터)

**목표**: 모더나이제이션 추가 전에, core 스택이 맥에서 실제로 뜨는지 확인.

```bash
# 리포 준비
git clone https://github.com/prgrms-be-devcourse/NBE10-12-3-Team7.git
cd NBE10-12-3-Team7 && git checkout feat/onprem-modernization

# 앱 이미지용 JAR 선행 빌드(필수)
cd backend && ./gradlew clean build -x test && cd ..

# core 스택
cd infra/onprem
cp .env.example .env          # 값 채우기(MAIL_* 등). .env 는 커밋 금지
docker compose --env-file .env up -d --build
```

**검증**: `http://localhost` 접속 / `http://localhost/api/...` / `docker compose ps` 전부 healthy. Ollama는 `ollama-pull` 완료 후 app이 뜬다.
**보고**: `docker compose ps` 출력.

---

# Step 1 — Zot (이미지 레지스트리, ECR 대응)

**목표**: onprem 스택에 사설 레지스트리 Zot을 넣고, app/next 이미지를 Zot에 push → compose가 **Zot에서 pull**하도록 전환.

### 1-1. Zot config + compose 서비스

`infra/onprem/zot/config.json` 생성:
```json
{
  "storage": { "rootDirectory": "/var/lib/registry" },
  "http": { "address": "0.0.0.0", "port": "5000" },
  "log": { "level": "info" }
}
```

`infra/onprem/docker-compose.yml`에 서비스 추가:
```yaml
  zot:
    image: ghcr.io/project-zot/zot:latest   # 멀티아치 → arm64 자동
    container_name: dongne-zot
    restart: unless-stopped
    ports:
      - "5000:5000"
    volumes:
      - ./zot/config.json:/etc/zot/config.json:ro
      - dongne-zot-data:/var/lib/registry
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:5000/v2/ || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
```
`volumes:` 블록 하단에 `dongne-zot-data:` 추가.

### 1-2. app/next 를 build → Zot pull 로 전환

현재 app/next는 `build:` + `image:`로 로컬 빌드한다. 이를 **pull 방식**으로 바꾼다:
- `app`, `next` 서비스에서 `build:` 블록을 제거(또는 별도 `build` 프로파일로 격리)하고
  `image: ${IMAGE_REGISTRY:-localhost:5000}/dongnemarket-app:${IMAGE_TAG:-latest}` (next도 동일 패턴).
- `.env.example`에 `IMAGE_REGISTRY=localhost:5000`, `IMAGE_TAG=latest` 추가.

부트스트랩용 빌드·푸시 스크립트 `infra/onprem/build-and-push.sh` 작성:
```bash
#!/usr/bin/env bash
set -euo pipefail
REG="${IMAGE_REGISTRY:-localhost:5000}"; TAG="${IMAGE_TAG:-latest}"
( cd ../../backend && ./gradlew clean build -x test )
docker build -t "$REG/dongnemarket-app:$TAG"  ../../backend
docker build -t "$REG/dongnemarket-next:$TAG" ../../frontend
docker push "$REG/dongnemarket-app:$TAG"
docker push "$REG/dongnemarket-next:$TAG"
```

### 1-3. 기동 순서

```bash
docker compose --env-file .env up -d zot           # 레지스트리 먼저
curl -s localhost:5000/v2/ && echo OK               # {} 확인
./build-and-push.sh                                 # 이미지 → Zot
curl -s localhost:5000/v2/_catalog                  # dongnemarket-app/next 확인
docker compose --env-file .env up -d                # 나머지: app/next는 Zot에서 pull
```

**검증**: `_catalog`에 `dongnemarket-app`·`dongnemarket-next` 등장 + `docker compose ps`에서 app/next가 `localhost:5000/...` 이미지로 떠 있음.
**보고**: `_catalog` 출력 + `docker compose images` 출력.

---

# Step 2 — RustFS (오브젝트 스토리지, S3 대응) + 백엔드 코드

**목표**: S3 호환 스토리지 RustFS를 올리고, 백엔드 파일 저장을 `local` → `s3(RustFS)` 로 전환.

### 2-1. RustFS compose 서비스

> 근거: RustFS 공식(멀티아치 amd64/arm64 ✅, S3 API `:9000`, 콘솔 `:9001`, 비루트 UID/GID `10001`). MinIO 호환.
> ⚠️ **루트 자격증명 env 변수명은 RustFS 문서에서 최종 확인**할 것(`RUSTFS_ACCESS_KEY`/`RUSTFS_SECRET_KEY` 계열로 추정 — 실제 기동 로그로 검증). named volume 사용(비루트 권한 이슈 회피).

```yaml
  rustfs:
    image: rustfs/rustfs:latest      # 필요시 1.0.0-beta.12 로 핀
    container_name: dongne-rustfs
    restart: unless-stopped
    ports:
      - "9000:9000"   # S3 API
      - "9001:9001"   # 콘솔
    environment:
      RUSTFS_ACCESS_KEY: ${RUSTFS_ACCESS_KEY:-rustfsadmin}   # ← 변수명 확인 후 확정
      RUSTFS_SECRET_KEY: ${RUSTFS_SECRET_KEY:-rustfsadmin}
    volumes:
      - dongne-rustfs-data:/data
      - dongne-rustfs-logs:/logs
```
`volumes:`에 `dongne-rustfs-data:`, `dongne-rustfs-logs:` 추가.
기동 후 콘솔(`http://localhost:9001`)에서 **버킷 `marketon-images` 생성**.

### 2-2. 백엔드 S3Config — endpoint 분기 (핵심 코드 변경)

현재 `backend/src/main/java/com/dongnemarket/global/storage/S3Config.java`는 AWS 전용:
```java
S3Client.builder()
    .region(Region.of(region))
    .credentialsProvider(DefaultCredentialsProvider.builder().build())
    .build();
```
이는 **실 AWS(EC2 IAM 역할·~/.aws)** 를 가정한다. RustFS는 **정적 자격증명 + endpoint + path-style**이 필요하므로, **`file.storage.s3.endpoint` 프로퍼티 유무로 분기**한다(클라우드 경로는 그대로 두어 회귀 없음):

- `file.storage.s3.endpoint`가 **있으면**(온프레미스/RustFS):
  - `.endpointOverride(URI.create(endpoint))`
  - `.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))`
  - `.forcePathStyle(true)` (RustFS/MinIO는 path-style)
- **없으면**(AWS 클라우드): 지금 코드 그대로(DefaultCredentialsProvider, path-style 미설정).

필요한 새 프로퍼티(`@Value`, 없으면 빈 문자열 기본):
`file.storage.s3.endpoint`, `file.storage.s3.access-key`, `file.storage.s3.secret-key`.
`application.yml`의 `file.storage.s3.*`에 위 키를 추가하고 env로 주입:
`FILE_STORAGE_S3_ENDPOINT`, `FILE_STORAGE_S3_ACCESS_KEY`, `FILE_STORAGE_S3_SECRET_KEY`.

> 기존 `S3FileStorageServiceTest`가 깨지지 않는지 확인. 테스트는 endpoint 미설정(AWS 경로) 기준이므로 분기 기본값이 그 경로를 타야 한다.

### 2-3. app 서비스 env 전환 (onprem compose)

```yaml
    environment:
      FILE_STORAGE_TYPE: s3
      AWS_S3_BUCKET: ${AWS_S3_BUCKET:-marketon-images}
      AWS_REGION: ${AWS_REGION:-us-east-1}          # RustFS는 아무 region이나 무방
      FILE_STORAGE_S3_ENDPOINT: http://rustfs:9000  # ← 이 값이 있으면 RustFS 경로로 분기
      FILE_STORAGE_S3_ACCESS_KEY: ${RUSTFS_ACCESS_KEY:-rustfsadmin}
      FILE_STORAGE_S3_SECRET_KEY: ${RUSTFS_SECRET_KEY:-rustfsadmin}
```
app `depends_on`에 `rustfs` 추가. `.env.example`에 위 키들 반영.

**검증**: 이미지 업로드가 필요한 플로우(상품 등록/신고 증빙)에서 파일이 RustFS 버킷에 실제로 쌓이는지 콘솔(`:9001`)에서 확인. 이미지 URL 조회가 200.
**보고**: 이미지 업로드→조회 왕복 결과 + 콘솔 스크린샷/오브젝트 목록.

---

# Step 3 — GHA self-hosted runner (CI/CD 대응)

**목표**: develop push 시 **맥에서 직접** 이미지 빌드→Zot push→재기동하는 온프레미스 CD 파이프라인.

### 3-1. 러너 등록
GitHub 리포 `Settings → Actions → Runners → New self-hosted runner (macOS/ARM64)` 안내대로 맥에 등록. 라벨 예: `onprem-mac`.
- 서비스로 상주: `./svc.sh install && ./svc.sh start` (재부팅 후에도 유지).

### 3-2. 워크플로 `.github/workflows/cd-onprem.yml`
```yaml
name: cd-onprem
on:
  push:
    branches: [develop]
jobs:
  deploy:
    runs-on: [self-hosted, onprem-mac]
    steps:
      - uses: actions/checkout@v4
      - name: Build & push images to Zot
        working-directory: infra/onprem
        run: ./build-and-push.sh
      - name: Restart stack (pull from Zot)
        working-directory: infra/onprem
        run: docker compose --env-file .env pull && docker compose --env-file .env up -d
```

### 3-3. ⚠️ 보안 (반드시 지킬 것)
- **self-hosted runner는 public 리포에서 위험**하다(포크 PR이 러너에서 임의 코드 실행 가능). 그래서 트리거를 **`push` to `develop`로 한정**하고, `pull_request` 트리거는 절대 붙이지 않는다.
- 리포가 public이면 `Settings → Actions → Fork pull request workflows`에서 포크 실행을 차단.
- 위험이 커지면 메모리 원안대로 **Jenkins로 대체** 검토.

**검증**: develop에 트리비얼 커밋 push → `cd-onprem` 성공 → 맥 스택이 새 이미지로 재기동.
**보고**: Actions 실행 로그 링크 + 재기동 후 `docker compose images`.

---

# Step 4 — 외부 공개 + 부하테스트

### 4-1. cloudflared 퀵터널 (이미 `edge` 프로파일에 있음)
```bash
docker compose --env-file .env --profile observability --profile edge up -d
docker logs dongne-cloudflared 2>&1 | grep trycloudflare   # 외부 임시 URL
```

### 4-2. k6 부하테스트 (별도 노트북에서)
- **맥이 아니라 다른 노트북에서** 실행(호스트 자원 경쟁 방지).
- Cloudflare 터널을 우회하고 **LAN에서 맥 IP로 직접**(`http://<맥 LAN IP>`) 때린다.
- 결과 p95/p99는 Prometheus remote-write로 Grafana에서 확인(compose에 `--web.enable-remote-write-receiver` 이미 설정됨).

**보고**: k6 요약(vus/rps/p95/p99) + Grafana 패널.

---

# 완료 기준 (최종 체크리스트)

- [ ] Step 0: core 스택 `http://localhost` 정상
- [ ] Step 1: `_catalog`에 app/next, compose가 Zot pull로 기동
- [ ] Step 2: 파일 업로드가 RustFS 버킷에 저장, `S3FileStorageServiceTest` 통과
- [ ] Step 3: `cd-onprem` push→배포 성공, 러너 상주
- [ ] Step 4: cloudflared 외부 URL + k6 LAN 부하 결과

# 경계 · 주의
- **인프라 경계**: 이번엔 `S3Config.java` + `application.yml`까지가 승인된 앱 코드 변경 범위. 그 외 도메인 로직은 건드리지 않는다.
- `.env`는 커밋 금지(gitignore). `.env.example`에만 키 목록.
- 배포 구성이 바뀌면 `infra/infra.md`를 **같은 PR에서** 갱신.
- 막히면 각 스텝 **검증 로그 원문**과 함께 보고. 임의로 범위를 넓히지 말 것.
