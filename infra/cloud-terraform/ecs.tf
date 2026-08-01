resource "aws_ecs_cluster" "main" {
  name = "${var.project}-cluster"
  setting {
    name  = "containerInsights"
    value = "enabled" # 컨테이너 메트릭 수집. 빼면 기본 메트릭만.
  }
  tags = { Name = "${var.project}-cluster" }
}
