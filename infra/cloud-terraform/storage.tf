# 버킷 이름은 전 세계 공유 네임스페이스 → 계정 ID를 붙여 전역 유일하게 만든다.
data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "images" {
  bucket        = "${var.project}-images-${data.aws_caller_identity.current.account_id}"
  force_destroy = true # 객체 있어도 버킷 삭제 허용(포폴/재구축). 운영은 false.
  tags          = { Name = "${var.project}-images" }
}

# 퍼블릭 접근 전면 차단. 이미지는 앱/서명URL로만 접근. 빼면 실수로 공개될 여지가 생긴다.
resource "aws_s3_bucket_public_access_block" "images" {
  bucket                  = aws_s3_bucket.images.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
