resource "aws_ecr_repository" "app" {
  name                 = "${var.project}-app"
  image_tag_mutability = "MUTABLE" # latest 갱신 허용. IMMUTABLE이면 같은 태그 재푸시 불가.
  image_scanning_configuration {
    scan_on_push = true # 푸시 시 취약점 스캔. 빼면 스캔 안 함.
  }
  force_delete = true # 이미지 남아도 repo 삭제 허용(포폴/재구축). 운영은 false.
  tags         = { Name = "${var.project}-app" }
}

resource "aws_ecr_repository" "next" {
  name                 = "${var.project}-next"
  image_tag_mutability = "MUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  force_delete = true
  tags         = { Name = "${var.project}-next" }
}
