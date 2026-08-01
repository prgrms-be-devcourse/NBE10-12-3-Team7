package com.dongnemarket.admin.controller

import com.dongnemarket.admin.dto.AdminReportResponse
import com.dongnemarket.admin.dto.AdminReportStatusUpdateRequest
import com.dongnemarket.admin.service.AdminReportService
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin - Report", description = "관리자 신고 관리 API")
@RestController
@RequestMapping("/api/admin/reports")
class AdminReportController(
    private val adminReportService: AdminReportService,
) {
    @Operation(summary = "신고 목록 조회", description = "관리자가 전체 신고 내역을 조회한다.")
    @GetMapping
    fun getReports(): ResponseEntity<ApiResponse<List<AdminReportResponse>>> =
        ResponseEntity.ok(ApiResponse.success(adminReportService.getReports()))

    @Operation(summary = "신고 상세 조회", description = "관리자가 신고 단건 정보를 조회한다.")
    @GetMapping("/{reportId}")
    fun getReport(
        @PathVariable reportId: Long,
    ): ResponseEntity<ApiResponse<AdminReportResponse>> = ResponseEntity.ok(ApiResponse.success(adminReportService.getReport(reportId)))

    @Operation(
        summary = "신고 상태 변경",
        description = "관리자가 신고 상태를 RECEIVED/REVIEWING/COMPLETED/REJECTED 로 변경한다.",
    )
    @PatchMapping("/{reportId}/status")
    fun changeReportStatus(
        @PathVariable reportId: Long,
        @RequestBody request: AdminReportStatusUpdateRequest,
    ): ResponseEntity<ApiResponse<AdminReportResponse>> =
        ResponseEntity.ok(ApiResponse.success(adminReportService.changeReportStatus(reportId, request)))
}
