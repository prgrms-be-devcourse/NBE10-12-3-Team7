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

## 다음 세션 시작점

- [x] **Step 0** 스캐폴드 — `versions.tf` · `providers.tf` · `variables.tf` · `.gitignore`
- [ ] **Step 1** network — VPC · subnet(2a·2b) · IGW · route table · security group  ← **여기부터**

재개 명령:

```bash
cd infra/cloud-terraform
tofu init        # 프로바이더 내려받기(AWS 호출 없음, 과금 없음)
tofu validate    # 문법·구성 검증
```
