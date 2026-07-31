package com.dongnemarket.report.controller

import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.response.ApiResponse
import com.dongnemarket.global.response.ErrorResponse
import com.dongnemarket.report.dto.EvidenceImageUploadResponse
import com.dongnemarket.report.dto.MemberReportCreateRequest
import com.dongnemarket.report.dto.MyReportResponse
import com.dongnemarket.report.dto.ProductReportCreateRequest
import com.dongnemarket.report.dto.ReportResponse
import com.dongnemarket.report.service.ReportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartFile
import java.net.URLConnection

@Tag(name = "Report", description = "신고 API")
@RestController
class ReportController(
    private val reportService: ReportService,
) {
    @Operation(summary = "상품 신고", description = "부적절한 상품을 신고한다.")
    @PostMapping("/api/products/{productId}/reports")
    fun reportProduct(
        @PathVariable productId: Long,
        @Valid @RequestBody request: ProductReportCreateRequest,
        @AuthenticationPrincipal reporterId: Long,
    ): ResponseEntity<ApiResponse<ReportResponse>> {
        val response = reportService.reportProduct(reporterId, productId, request)
        return ResponseEntity.status(201).body(ApiResponse.success(201, "신고가 접수되었습니다.", response))
    }

    @Operation(summary = "사용자 신고", description = "부적절한 사용자를 신고한다.")
    @PostMapping("/api/members/{memberId}/reports")
    fun reportMember(
        @PathVariable memberId: Long,
        @Valid @RequestBody request: MemberReportCreateRequest,
        @AuthenticationPrincipal reporterId: Long,
    ): ResponseEntity<ApiResponse<ReportResponse>> {
        val response = reportService.reportMember(reporterId, memberId, request)
        return ResponseEntity.status(201).body(ApiResponse.success(201, "신고가 접수되었습니다.", response))
    }

    @Operation(summary = "내 신고 내역 조회", description = "내가 작성한 신고 목록을 조회한다.")
    @GetMapping("/api/members/me/reports")
    fun getMyReports(
        @AuthenticationPrincipal reporterId: Long,
    ): ResponseEntity<ApiResponse<List<MyReportResponse>>> {
        val response = reportService.getMyReports(reporterId)
        return ResponseEntity.ok(ApiResponse.success("목록 조회에 성공했습니다.", response))
    }

    @Operation(summary = "내 신고 상세 조회", description = "내가 작성한 신고 한 건의 상세 내용(사유 상세 포함)을 조회한다.")
    @GetMapping("/api/members/me/reports/{reportId}")
    fun getMyReport(
        @PathVariable reportId: Long,
        @AuthenticationPrincipal reporterId: Long,
    ): ResponseEntity<ApiResponse<MyReportResponse>> {
        val response = reportService.getMyReport(reporterId, reportId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(summary = "신고 취소", description = "아직 처리되지 않은(RECEIVED) 내 신고를 취소한다.")
    @DeleteMapping("/api/members/me/reports/{reportId}")
    fun cancelReport(
        @PathVariable reportId: Long,
        @AuthenticationPrincipal reporterId: Long,
    ): ResponseEntity<ApiResponse<Void>> {
        reportService.cancelReport(reporterId, reportId)
        return ResponseEntity.ok(ApiResponse.success())
    }

    @Operation(summary = "신고 증빙 이미지 업로드", description = "신고 작성 시 첨부할 증빙 이미지를 업로드하고 접근 URL을 반환한다.")
    @PostMapping(value = ["/api/reports/evidence-image"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadEvidenceImage(
        @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<ApiResponse<EvidenceImageUploadResponse>> {
        val response = reportService.uploadEvidenceImage(file)
        return ResponseEntity.status(201).body(ApiResponse.success(201, "이미지를 업로드했습니다.", response))
    }

    @Operation(summary = "신고 증빙 이미지 조회", description = "업로드된 신고 증빙 이미지 파일을 반환한다.")
    @GetMapping("/api/reports/evidence-image/{filename}")
    fun getEvidenceImage(
        @PathVariable filename: String,
    ): ResponseEntity<Resource> {
        val resource = reportService.loadEvidenceImage(filename)
        val contentType = URLConnection.guessContentTypeFromName(filename)
        val mediaType = if (contentType != null) MediaType.parseMediaType(contentType) else MediaType.APPLICATION_OCTET_STREAM
        return ResponseEntity.ok().contentType(mediaType).body(resource)
    }

    /**
     * spring.servlet.multipart.max-file-size 초과 시 서블릿 계층에서 컨트롤러 메서드 진입 전에 던져지는 예외.
     * 전역 예외 처리기(GlobalExceptionHandler, 공통 영역)를 건드리지 않기 위해 이 컨트롤러에 국한해 처리한다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSizeExceeded(e: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(ErrorCode.INVALID_EVIDENCE_IMAGE.status)
            .body(ErrorResponse.of(ErrorCode.INVALID_EVIDENCE_IMAGE))
}
