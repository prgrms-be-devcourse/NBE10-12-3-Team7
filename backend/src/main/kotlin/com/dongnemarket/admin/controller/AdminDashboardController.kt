package com.dongnemarket.admin.controller

import com.dongnemarket.admin.dto.AdminDashboardResponse
import com.dongnemarket.admin.service.AdminDashboardService
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin - Dashboard", description = "관리자 대시보드 API")
@RestController
@RequestMapping("/api/admin/dashboard")
class AdminDashboardController(
    private val adminDashboardService: AdminDashboardService,
) {
    @Operation(summary = "관리 대시보드 조회", description = "회원·상품·신고·댓글 집계를 조회한다.")
    @GetMapping
    fun getDashboard(): ResponseEntity<ApiResponse<AdminDashboardResponse>> =
        ResponseEntity.ok(ApiResponse.success(adminDashboardService.getDashboard()))
}
