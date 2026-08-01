# "누가 이 역할을 맡을 수 있나" — ECS 태스크 서비스만 허용.
data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# ① 실행 역할(execution) — ECS 에이전트가 "컨테이너 시작 준비"에 쓴다: ECR pull·로그·시크릿 주입.
resource "aws_iam_role" "ecs_execution" {
  name               = "${var.project}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}
resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy" # AWS 관리형 표준
}
# 시크릿을 env로 주입하려면 GetSecretValue 추가 필요
data "aws_iam_policy_document" "ecs_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_secretsmanager_secret.app.arn,
      aws_db_instance.mysql.master_user_secret[0].secret_arn, # RDS 관리형 DB비번 시크릿
    ]
  }
}
resource "aws_iam_role_policy" "ecs_secrets" {
  name   = "${var.project}-ecs-secrets"
  role   = aws_iam_role.ecs_execution.id
  policy = data.aws_iam_policy_document.ecs_secrets.json
}

# ② 태스크 역할(task) — 실행 중인 "앱 컨테이너 자신"이 AWS를 부를 때 쓰는 신원(S3 업로드 등).
resource "aws_iam_role" "ecs_task" {
  name               = "${var.project}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}
data "aws_iam_policy_document" "app_s3" {
  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket"]
    resources = [aws_s3_bucket.images.arn, "${aws_s3_bucket.images.arn}/*"]
  }
}
resource "aws_iam_role_policy" "app_s3" {
  name   = "${var.project}-app-s3"
  role   = aws_iam_role.ecs_task.id
  policy = data.aws_iam_policy_document.app_s3.json
}
