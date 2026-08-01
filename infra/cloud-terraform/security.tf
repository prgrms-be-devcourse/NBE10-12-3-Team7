# ECS 태스크(app)가 붙을 SG. 인바운드(ALB로부터)는 ECS/ALB 단계에서 추가한다.
resource "aws_security_group" "app" {
  name        = "${var.project}-app-sg"
  description = "ECS app tasks"
  vpc_id      = aws_vpc.main.id
  egress { # 나가기 전체 허용(ECR pull·외부 SMTP/OAuth). 빼면 이미지도 못 당긴다.
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.project}-app-sg" }
}

# RDS: 3306을 app SG에서 오는 것만 허용. 소스가 CIDR이 아니라 SG 참조 → "app만 DB에 닿는다".
resource "aws_security_group" "rds" {
  name        = "${var.project}-rds-sg"
  description = "MySQL from app only"
  vpc_id      = aws_vpc.main.id
  ingress {
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id] # IP가 바뀌어도 유효. 빼면 아무도 못 붙거나 열어버림.
  }
  # egress 없음 = 의도됨. SG는 stateful이라 허용된 인바운드의 응답은 자동으로 나간다(DB는 먼저 나갈 일 없음).
  tags = { Name = "${var.project}-rds-sg" }
}

# Redis: 6379를 app SG에서 오는 것만 허용.
resource "aws_security_group" "redis" {
  name        = "${var.project}-redis-sg"
  description = "Redis from app only"
  vpc_id      = aws_vpc.main.id
  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }
  tags = { Name = "${var.project}-redis-sg" }
}
