# infra/cloud-terraform — AWS 미러 (OpenTofu · 코드 전용)

> 상위: [../infra.md](../infra.md) · 대응되는 실제 운영 스택: [../onprem/README.md](../onprem/README.md)

온프레미스 스택을 AWS **관리형 서비스**로 대응시킨 포트폴리오 IaC.
**apply·운영하지 않는다 — 코드 자체가 산출물이다.** 실 운영은 온프레미스(맥 호스트)에서 한다.

**온프레미스 ↔ AWS 대응 관계의 정본은 [../infra.md](../infra.md) 의 대응표다.**
두 곳에서 관리하면 반드시 어긋나므로 여기서는 반복하지 않는다.

## 코드 지도

파일은 **관심사 하나에 하나씩** 나눴다. 아래는 각 파일이 실제로 만드는 것이다.

| 파일 | 만드는 것 |
|---|---|
| `versions.tf` | 실행기(tofu ≥1.6)·프로바이더(aws ~>6.0) 버전 고정 |
| `providers.tf` | AWS 프로바이더 — 리전, 전 자원 공통 태그(`Project`·`Environment`·`ManagedBy`) |
| `variables.tf` | 변수 7개. **전부 기본값이 있어** `plan` 이 입력을 요구하지 않는다 |
| `network.tf` | VPC · public/private 서브넷 각 2 · IGW · 라우트 (**NAT 게이트웨이 없음**) |
| `security.tf` | 보안 그룹 4종 — app · next · rds · redis |
| **`data.tf`** | ⚠️ **데이터 티어**다 — RDS MySQL · ElastiCache Redis · 각 서브넷 그룹 |
| `ecr.tf` | ECR 리포지터리 2개 (app · next) |
| `storage.tf` | S3 버킷 + **퍼블릭 접근 전면 차단** |
| `secrets.tf` | Secrets Manager 시크릿 1개 |
| `ecs.tf` | ECS 클러스터 |
| `iam.tf` | ECS 실행·태스크 역할 |
| `logs.tf` | CloudWatch 로그 그룹 2개 |
| `taskdef.tf` | ECS 태스크 정의 2개 (app · next) |
| `services.tf` | ECS 서비스 2개 |
| `alb.tf` | ALB · 타깃 그룹 2 · 리스너 · 리스너 규칙 · ALB 보안 그룹 |
| `dns.tf` | Route 53 존·레코드 · ACM 인증서(DNS 검증) · HTTPS 리스너 |
| `ec2-ollama.tf` | **유일한 EC2** — Ollama(t3.medium) · 전용 SG · SSM 인스턴스 프로파일 |
| `monitoring.tf` | CloudWatch 대시보드 1 · 알람 3(ALB 5xx · ECS CPU · RDS CPU) · SNS 토픽 |
| `outputs.tf` | `app_url` · `alb_dns_name` · ECR URL 2 · `route53_nameservers` |

> **`data.tf` 는 이름이 헷갈린다.** Terraform 관례로는 `data.tf` 가 *데이터 소스* 파일이지만
> 여기서는 **데이터 티어(RDS·Redis)** 를 담고 있다. 실제 데이터 소스(`aws_availability_zones`,
> `aws_caller_identity`, `aws_ami`, `aws_iam_policy_document`)는 각각 그것을 쓰는 파일 안에 있다.

**EC2는 Ollama 한 대뿐이다.** 앱은 ECS Fargate, DB는 RDS, 모니터링은 CloudWatch 관리형이다 —
어느 것도 EC2에 올리지 않는다.

## 상태 관리

원격 state(S3+DynamoDB)는 **의도적으로 생략**한다 — apply를 안 하므로 로컬 기본 백엔드로 충분.
검증은 `fmt` · `validate` · `plan` 까지만 한다.

프로바이더 버전은 `versions.tf` 가 범위(`~> 6.0`)로 잡고 **`.terraform.lock.hcl` 이 정확한
버전을 고정한다.** lock 파일은 커밋되어 있으므로 다른 사람이 `tofu init` 해도 같은
프로바이더가 내려온다.

## 도구

- **OpenTofu** (Terraform의 오픈소스 포크). 명령은 `tofu <sub>` (예: `tofu init`, `tofu plan`).
- 설치(Windows): `winget install --id <조회한 패키지 id> -e`

## 진행 상태 — 전체 완료 (코드 전용, apply 안 함)

- [x] **Step 0** 스캐폴드 — `versions.tf` · `providers.tf` · `variables.tf`
- [x] **Step 1** network — VPC · subnet(public/private ×2) · IGW · route (NAT 없이 IGW egress)
- [x] **Step 2** data — RDS MySQL · ElastiCache Redis · SG 3종(app만 접근)
- [x] **Step 3** registry/storage/secrets — ECR ×2 · S3(퍼블릭 차단) · Secrets Manager
- [x] **Step 4** ecs — cluster · IAM(실행/태스크) · logs · task def(app·next)
- [x] **Step 5** alb · ecs service — ALB·타깃그룹·리스너, 서비스 2개(퍼블릭+공인IP)
- [x] **Step 6** ec2(Ollama qwen3:1.7b) · **CloudWatch 관리형 모니터링**(대시보드·알람·SNS)
- [x] **Step 7** dns — Route53 · ACM(DNS 검증) · HTTPS(443, 80→443 리다이렉트)

> 위는 **만든 순서**다. 파일 구조는 위의 "코드 지도"를 본다.
> Step 2의 "SG 3종"은 그 시점 기준이고, Step 5·6에서 alb·next·ollama 것이 더해져 **최종 6종**이다.

**최종 `plan`: 58 resources to add, 0 destroy** (전 단계 `validate` 통과).
`resource` 블록은 55개인데, 서브넷 2종과 라우트 연결이 `count = 2` 라 셋이 각각 하나씩 늘어난다.

재개/검증 명령:

```bash
cd infra/cloud-terraform
tofu init && tofu validate && tofu plan   # 58 to add
```

> 실 운영 전환 시: `versions.tf`에 `backend "s3"` 추가 + `tofu apply`. 도메인은 `route53_nameservers` 출력값을 상위 등록기관에 위임 등록해야 해석됨.

## 함정 기록

- **gitignore 인라인 주석 미지원** — `.terraform/  # 주석`처럼 패턴 뒤에 주석을 붙이면 패턴 전체가
  깨져 무시가 안 된다(수백 MB 프로바이더 바이너리가 커밋에 딸려옴). 주석은 반드시 **별도 줄**로.
