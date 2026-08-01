# infra/cloud-terraform — AWS 미러 (OpenTofu · 코드 전용)

온프레미스 스택을 AWS **관리형 서비스**로 대응시킨 포트폴리오 IaC.
**apply·운영하지 않는다 — 코드 자체가 산출물이다.** 실 운영은 온프레미스(맥 호스트)에서 한다.

## 온프레미스 ↔ AWS 대응

| AWS | 온프레미스 |
|---|---|
| ALB | nginx |
| ECS Fargate (app·next) | Docker Compose (app·next) |
| RDS MySQL | MySQL 컨테이너 |
| ElastiCache Redis | Redis 컨테이너 |
| S3 | RustFS |
| ECR | Zot |
| Route 53 · ACM | cloudflared |
| EC2 (Ollama·모니터링) | Ollama·observability 컨테이너 |

## 상태 관리

원격 state(S3+DynamoDB)는 **의도적으로 생략**한다 — apply를 안 하므로 로컬 기본 백엔드로 충분.
검증은 `fmt` · `validate` · `plan` 까지만 한다.

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

**최종 `plan`: 58 resources to add, 0 destroy** (전 단계 `validate` 통과).

재개/검증 명령:

```bash
cd infra/cloud-terraform
tofu init && tofu validate && tofu plan   # 58 to add
```

> 실 운영 전환 시: `versions.tf`에 `backend "s3"` 추가 + `tofu apply`. 도메인은 `route53_nameservers` 출력값을 상위 등록기관에 위임 등록해야 해석됨.

## 함정 기록

- **gitignore 인라인 주석 미지원** — `.terraform/  # 주석`처럼 패턴 뒤에 주석을 붙이면 패턴 전체가
  깨져 무시가 안 된다(수백 MB 프로바이더 바이너리가 커밋에 딸려옴). 주석은 반드시 **별도 줄**로.
