# 이 코드가 "무엇으로" 실행되는지를 못박는 파일. 실행기(tofu) 버전과 프로바이더를 고정한다.
# (조회 기준 2026-07-31: OpenTofu 1.12.5 · hashicorp/aws 6.57.1)
terraform {
  # tofu 바이너리 최소 버전. 빼면 아무 버전에서나 돌아가 재현성이 깨진다
  # — 팀원이 구버전 tofu를 쓰면 같은 코드가 다르게 동작할 수 있다.
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws" # 레지스트리 주소. 빼면 "aws"가 어느 프로바이더인지 못 찾는다(로컬 이름≠출처).
      version = "~> 6.0"        # >=6.0, <7.0 만 허용. 빼면 7.0 같은 메이저가 자동 유입돼 breaking change로 깨질 수 있다.
    }
  }

  # 원격 state(S3+DynamoDB)는 의도적으로 걸지 않는다 — 이 프로젝트는 apply를 하지 않으므로
  # 로컬 기본 백엔드로 충분하다. 실제 운영으로 전환할 때 backend "s3" 블록을 추가한다.
}
