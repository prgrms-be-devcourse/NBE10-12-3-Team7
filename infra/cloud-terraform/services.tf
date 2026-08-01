resource "aws_ecs_service" "app" {
  name            = "${var.project}-app"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = 1 # 이 개수를 항상 유지(죽으면 재기동). 늘리면 수평 확장.
  launch_type     = "FARGATE"
  network_configuration {
    subnets          = aws_subnet.public[*].id # 퍼블릭 + 공인IP → NAT 없이 ECR/외부 통신
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true # 빼면 egress 불가(우린 NAT 안 씀). SG가 인바운드는 ALB만 허용.
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "app"
    container_port   = 8080
  }
  depends_on = [aws_lb_listener.http] # 리스너 준비 전 등록하면 실패
}

resource "aws_ecs_service" "next" {
  name            = "${var.project}-next"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.next.arn
  desired_count   = 1
  launch_type     = "FARGATE"
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.next.id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.next.arn
    container_name   = "next"
    container_port   = 3000
  }
  depends_on = [aws_lb_listener.http]
}
