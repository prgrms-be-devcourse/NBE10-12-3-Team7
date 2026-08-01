output "app_url" {
  description = "서비스 접속 주소"
  value       = "https://${var.domain_name}"
}

output "alb_dns_name" {
  description = "ALB 기본 DNS(도메인 위임 전 임시 접속용)"
  value       = aws_lb.main.dns_name
}

output "ecr_app_url" {
  description = "백엔드 이미지 push 대상"
  value       = aws_ecr_repository.app.repository_url
}

output "ecr_next_url" {
  description = "프론트 이미지 push 대상"
  value       = aws_ecr_repository.next.repository_url
}

# 이 네임서버들을 상위 등록기관(inyeon.io)에 위임 등록해야 도메인이 해석된다.
output "route53_nameservers" {
  description = "도메인 위임에 등록할 NS 레코드"
  value       = aws_route53_zone.main.name_servers
}
