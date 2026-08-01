# required_providers가 "무엇을" 쓰는지라면, 이 파일은 그 프로바이더를 "어떻게" 설정하는지다.
provider "aws" {
  region = var.aws_region # 어느 리전에 자원을 만드는가. 빼면 환경변수(AWS_REGION)나 ~/.aws/config에 의존 → 실행 환경마다 달라진다.

  # 이 프로바이더로 만드는 모든 자원에 자동으로 붙는 태그.
  # 빼도 동작하지만, 붙이면 콘솔·비용 탐색기에서 "OpenTofu가 만든 marketon 자원"을 한눈에 골라낸다(정리·비용추적의 핵심).
  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "OpenTofu"
    }
  }
}
