# CI/CD — 온프레미스 Jenkins

> 상위: [README.md](README.md) · [../infra.md](../infra.md)

**배포(CD)는 온프레미스 Jenkins가 담당한다** (`cicd` 프로파일).

```bash
cd infra/onprem
docker compose --env-file .env --profile cicd up -d --build jenkins
```

## 왜 GitHub Actions가 아닌가

GitHub Actions self-hosted runner는 쓰지 않는다. **이 리포는 public이라 포크 PR이 배포
호스트에서 임의 코드를 실행할 수 있다.** Jenkins는 GitHub이 밀어넣는 게 아니라 우리가
당겨오는 방향이라 그 경로가 없다.

같은 이유로 웹훅도 쓰지 않는다 — 웹훅을 받으려면 호스트를 외부에 노출해야 한다.

## 구성

| 항목 | 값 |
|---|---|
| 접속 | `http://localhost:8080` (계정은 `.env`의 `JENKINS_ADMIN_*`) |
| 잡 | `dongnemarket-onprem-cd` — `jenkins/casc.yaml`이 생성한다. **UI로 만들지 않는다** |
| 트리거 | SCM 폴링 `H/2 * * * *` |
| 추적 브랜치 | `.env`의 `JENKINS_TRACK_BRANCH` |
| 단계 | 빌드·Zot push → 배포 → 검증 (리포 루트 [`Jenkinsfile`](../../Jenkinsfile)) |
| 배포 태그 | `build-{빌드번호}` 고정 — 지금 뜬 게 어느 빌드인지 컨테이너만 봐도 안다 |

설정은 `jenkins/`(Dockerfile · plugins.txt · casc.yaml)가 소유한다. **컨테이너를 지우고
다시 만들어도 같은 상태로 복원된다.** UI에서 만든 잡은 그 복원에서 사라지므로 만들지 않는다.

## 파이프라인을 손댈 때 알아야 할 세 가지

여기서 막히면 원인을 찾기 어려운 것들이다. 전부 "같은 이름이 두 곳에서 다른 것을 가리킨다"는
문제다.

### ① 경로가 둘이다

빌드는 **Jenkins 워크스페이스**(방금 체크아웃한 커밋)에서, 배포는 **호스트 리포
경로**(`HOST_REPO_PATH`)에서 돈다.

컨테이너 안에서 `docker compose up`을 해도 **바인드 마운트는 호스트 기준으로 해석된다.**
워크스페이스에서 띄우면 `nginx.conf` 같은 파일을 못 찾는다.

### ② 레지스트리 주소도 둘이다

| 쓰이는 곳 | 주소 | 왜 |
|---|---|---|
| 이미지 태그 | `localhost:5000` | push/pull을 수행하는 주체는 CLI가 아니라 **호스트 도커 데몬**이다 |
| Jenkins 컨테이너 안의 사전 확인 curl | `zot:5000` | 컨테이너 네트워크에서 나가는 요청이다 |

이 둘을 `REGISTRY_PROBE`로 분리했다.

### ③ `docker.sock` 마운트 = 사실상 호스트 root 권한

개인 호스트에서 자기 리포만 빌드한다는 전제의 트레이드오프다. 공용 호스트라면
이 구성을 그대로 쓰면 안 된다.

## 테스트(CI)는 아직 자동화되어 있지 않다

| 항목 | 지금 |
|---|---|
| 테스트 게이트 | 각 담당자가 머지 전 `cd backend && ./gradlew test` 로 직접 확인 |
| 배포 | Jenkins가 추적 브랜치 push를 감지해 자동 배포 |

> ⚠️ **Java↔Kotlin 혼재 기간에는 도메인 간 컴파일 영향이 있다.** 예를 들어 `Member` 엔티티는
> 7개 도메인에서 34회 참조된다. 자기 브랜치 테스트로는 안 잡히므로 **develop 머지 직후
> 전체 테스트를 한 번 더** 돌린다.

## 재구축 시 반영할 항목

- 테스트 게이트 (`./gradlew test` on PR)
- Kotlin 마이그레이션 진척 카운터 (`.java`/`.kt` 집계 → Step Summary)
- ktlint 게이트 승격 — `backend/build.gradle` 의 `ignoreFailures = false`
- AWS 인증은 **OIDC**(`secrets.AWS_ROLE_ARN`), 배포 대상 `secrets.EC2_APP_HOST`,
  접속 키 `secrets.EC2_SSH_KEY`

> ⚠️ 리포를 이전하면 **IAM 역할의 신뢰 정책에 새 리포 경로를 허용**해야 한다.
> 안 하면 모든 배포가 OIDC 인증에서 실패한다.
