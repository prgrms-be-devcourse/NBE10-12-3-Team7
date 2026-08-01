# ── ALB 보안그룹: 인터넷 80을 받는 유일한 공개 지점. ──
resource "aws_security_group" "alb" {
  name        = "${var.project}-alb-sg"
  description = "ALB public HTTP"
  vpc_id      = aws_vpc.main.id
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"] # HTTPS(443)는 dns 단계에서 추가
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.project}-alb-sg" }
}

# 태스크 인바운드를 여기서 채운다 — "ALB에서 온 것만" 해당 포트로 허용(SG→SG 참조).
resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_ingress_rule" "next_from_alb" {
  security_group_id            = aws_security_group.next.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 3000
  to_port                      = 3000
  ip_protocol                  = "tcp"
}

# ── ALB 본체: 퍼블릭 서브넷 2a·2b에 걸침(Multi-AZ 진입). ──
resource "aws_lb" "main" {
  name               = "${var.project}-alb"
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id
  tags               = { Name = "${var.project}-alb" }
}

# ── 타깃그룹: Fargate는 IP 타깃(awsvpc라 인스턴스 아닌 태스크 IP로 등록). ──
resource "aws_lb_target_group" "app" {
  name        = "${var.project}-app-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"
  health_check {
    path    = "/actuator/health" # 앱 실제 헬스 엔드포인트에 맞춰 조정
    matcher = "200"
  }
}
resource "aws_lb_target_group" "next" {
  name        = "${var.project}-next-tg"
  port        = 3000
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"
  health_check {
    path    = "/"
    matcher = "200-399"
  }
}

# ── 리스너(80): 기본은 next, /api/* 만 app으로 분기. ──
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.next.arn # / → next
  }
}
resource "aws_lb_listener_rule" "api" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 100
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
  condition {
    path_pattern {
      values = ["/api/*"]
    }
  }
}
