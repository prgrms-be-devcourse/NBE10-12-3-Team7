# 앱 시크릿(JWT·메일 등) 보관함. 값은 코드/state에 넣지 않고 콘솔·CLI로 주입한다(유출 방지).
# ※ DB 마스터 비번은 RDS의 manage_master_user_password가 별도 시크릿으로 이미 자동 생성한다.
resource "aws_secretsmanager_secret" "app" {
  name        = "${var.project}-app-secrets"
  description = "App-level secrets (JWT, mail). Values injected out-of-band."
  tags        = { Name = "${var.project}-app-secrets" }
}
