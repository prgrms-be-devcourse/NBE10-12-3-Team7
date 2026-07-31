package com.dongnemarket.admin.controller

import com.dongnemarket.admin.dto.AdminMannerResponse
import com.dongnemarket.admin.service.AdminMannerService
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@Tag(name = "Admin - Manner", description = "관리자 매너온도(저신뢰 회원) 모니터링 API")
@RestController
@RequestMapping("/api/admin/manner-scores")
class AdminMannerController(
    private val adminMannerService: AdminMannerService,
) {
    /**
     * `threshold` 는 `required = false` 라 쿼리 파라미터가 없으면 Spring 이 null 을 넘긴다.
     * non-null 로 선언하면 파라미터를 생략한 요청에서 NPE 가 난다.
     */
    @Operation(
        summary = "저신뢰 회원 모니터링",
        description = "매너온도가 threshold 이하인 회원을 낮은 순으로 조회한다(기본 20.0).",
    )
    @GetMapping
    fun getLowTrustMembers(
        @RequestParam(required = false) threshold: BigDecimal?,
    ): ApiResponse<List<AdminMannerResponse>> = ApiResponse.success(adminMannerService.getLowTrustMembers(threshold))
}
