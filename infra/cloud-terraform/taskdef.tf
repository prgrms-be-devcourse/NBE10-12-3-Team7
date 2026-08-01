resource "aws_ecs_task_definition" "app" {
  family                   = "${var.project}-app"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc" # Fargate 필수. 각 태스크가 ENI(사설IP)를 받는다.
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.ecs_execution.arn # 시작 준비용(pull·로그·시크릿)
  task_role_arn            = aws_iam_role.ecs_task.arn      # 앱 실행 중 신원(S3)
  container_definitions = jsonencode([
    {
      name         = "app"
      image        = "${aws_ecr_repository.app.repository_url}:latest"
      essential    = true
      portMappings = [{ containerPort = 8080 }]
      # 평문이어도 되는 값 = environment
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "DB_URL", value = "jdbc:mysql://${aws_db_instance.mysql.address}:3306/${var.db_name}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8" },
        { name = "DB_USERNAME", value = var.db_username },
        { name = "REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "REDIS_PORT", value = "6379" },
        { name = "FILE_STORAGE_TYPE", value = "s3" },
        { name = "AWS_S3_BUCKET", value = aws_s3_bucket.images.bucket },
        { name = "AWS_REGION", value = var.aws_region },
        { name = "OLLAMA_BASE_URL", value = "http://${aws_instance.ollama.private_ip}:11434" },
        { name = "OLLAMA_MODEL", value = var.ollama_model },
      ]
      # 민감값 = secrets (평문 노출 없이 Secrets Manager에서 주입)
      secrets = [
        { name = "DB_PASSWORD", valueFrom = "${aws_db_instance.mysql.master_user_secret[0].secret_arn}:password::" },
        { name = "JWT_SECRET", valueFrom = "${aws_secretsmanager_secret.app.arn}:JWT_SECRET::" },
        { name = "MAIL_USERNAME", valueFrom = "${aws_secretsmanager_secret.app.arn}:MAIL_USERNAME::" },
        { name = "MAIL_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app.arn}:MAIL_PASSWORD::" },
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "app"
        }
      }
    }
  ])
  tags = { Name = "${var.project}-app" }
}

resource "aws_ecs_task_definition" "next" {
  family                   = "${var.project}-next"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  container_definitions = jsonencode([
    {
      name         = "next"
      image        = "${aws_ecr_repository.next.repository_url}:latest"
      essential    = true
      portMappings = [{ containerPort = 3000 }]
      environment  = [{ name = "NODE_ENV", value = "production" }]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.next.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "next"
        }
      }
    }
  ])
  tags = { Name = "${var.project}-next" }
}
