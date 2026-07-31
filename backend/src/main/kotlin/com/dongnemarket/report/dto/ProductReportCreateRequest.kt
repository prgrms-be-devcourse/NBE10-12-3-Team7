package com.dongnemarket.report.dto

import com.dongnemarket.report.entity.ReportReason
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * 검증 어노테이션은 `@Target`에 PARAMETER가 있어 생성자 파라미터가 우선 선택된다 — 검증은
 * 필드/getter를 읽으므로 `@field:`로 명시하지 않으면 검증이 걸리지 않을 수 있다(backend.md 참고).
 */
data class ProductReportCreateRequest(
    @field:NotNull(message = "신고 사유는 필수입니다.")
    val reason: ReportReason?,
    @field:Size(max = 500, message = "신고 내용은 500자 이하여야 합니다.")
    val content: String?,
    @field:Size(max = 500, message = "증빙 이미지 URL은 500자 이하여야 합니다.")
    val evidenceImageUrl: String?,
)
