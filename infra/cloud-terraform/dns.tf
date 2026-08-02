resource "aws_route53_zone" "main" {
  name = var.domain_name
  # inyeon.io가 외부 등록기관이면, 이 존의 NS를 상위에 위임해야 실제 해석된다(코드전용이라 plan은 됨).
}

# ACM 인증서(무료). DNS 검증 = 소유 증명을 레코드로(EMAIL보다 자동화 쉬움).
resource "aws_acm_certificate" "main" {
  domain_name       = var.domain_name
  validation_method = "DNS"
  lifecycle {
    create_before_destroy = true
  }
}

# ACM이 요구하는 검증 레코드를 Route53에 자동 생성.
resource "aws_route53_record" "cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.main.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      type   = dvo.resource_record_type
      record = dvo.resource_record_value
    }
  }
  zone_id = aws_route53_zone.main.zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.record]
  ttl     = 60
}

# 검증 완료를 기다린다(레코드 반영 → ACM 발급까지).
resource "aws_acm_certificate_validation" "main" {
  certificate_arn         = aws_acm_certificate.main.arn
  validation_record_fqdns = [for r in aws_route53_record.cert_validation : r.fqdn]
}

# 도메인 → ALB. IP가 바뀌는 ALB는 A "Alias"로 가리킨다(고정 IP 불필요 — Route53 특수 레코드).
resource "aws_route53_record" "app" {
  zone_id = aws_route53_zone.main.zone_id
  name    = var.domain_name
  type    = "A"
  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}

# HTTPS(443) 리스너 — 검증된 인증서로 ALB에서 TLS 종료. 기본은 next.
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.main.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.next.arn
  }
}
