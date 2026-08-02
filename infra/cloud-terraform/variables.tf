# 코드 전반에서 재사용하는 입력값. 값을 한 곳에 모아 "무엇을 바꾸면 무엇이 바뀌는가"를 명확히 한다.
variable "aws_region" {
  description = "배포 리전. 온프레미스(서울 LAN) 대응이므로 ap-northeast-2."
  type        = string
  default     = "ap-northeast-2"
}

variable "project" {
  description = "자원 이름·태그의 접두어. 한 계정에 여러 프로젝트가 섞일 때 구분자 역할."
  type        = string
  default     = "marketon"
}

variable "environment" {
  description = "환경 식별자. 이 코드는 온프레미스 prod 구조를 미러링하므로 prod로 표기."
  type        = string
  default     = "prod"
}

variable "db_name" {
  description = "앱이 접속하는 DB 이름. 프로젝트 전체 marketon 통일."
  type        = string
  default     = "marketon"
}

variable "db_username" {
  description = "DB 접속 유저. 프로젝트 전체 marketon 통일."
  type        = string
  default     = "marketon"
}

variable "ollama_model" {
  description = "관리자 AI 모델. 온프레미스와 통일."
  type        = string
  default     = "qwen3:1.7b"
}

variable "domain_name" {
  description = "서비스 도메인. Route53·ACM 대상."
  type        = string
  default     = "marketon.inyeon.io"
}
