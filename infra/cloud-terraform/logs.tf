resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.project}-app"
  retention_in_days = 14 # 보관 기간. 빼면 무기한 → 비용 누적.
  tags              = { Name = "${var.project}-app-logs" }
}

resource "aws_cloudwatch_log_group" "next" {
  name              = "/ecs/${var.project}-next"
  retention_in_days = 14
  tags              = { Name = "${var.project}-next-logs" }
}
