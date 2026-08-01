package com.dongnemarket.manner.controller

import com.dongnemarket.global.response.ApiResponse
import com.dongnemarket.manner.dto.MannerScoreHistoryResponse
import com.dongnemarket.manner.dto.MannerScoreResponse
import com.dongnemarket.manner.service.MannerScoreService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@Tag(name = "MannerScore", description = "매너온도 조회 API")
@RestController
class MannerScoreController(
    private val mannerScoreService: MannerScoreService,
) {
    @Operation(summary = "회원 매너온도 조회", description = "상품 상세·채팅방 등에서 노출할 공개 매너온도를 조회한다.")
    @GetMapping("/api/members/{memberId}/manner-score")
    fun getScore(
        @PathVariable memberId: Long,
    ): ApiResponse<MannerScoreResponse> = ApiResponse.success(MannerScoreResponse.from(mannerScoreService.getOrCreate(memberId)))

    @Operation(summary = "내 매너온도 변화 이력 조회", description = "내정보 페이지의 매너온도 변화 이력 타임라인용 목록을 조회한다.")
    @GetMapping("/api/members/me/manner-score/history")
    fun getMyHistory(
        @AuthenticationPrincipal memberId: Long,
    ): ApiResponse<List<MannerScoreHistoryResponse>> {
        val response = mannerScoreService.getHistory(memberId).map { MannerScoreHistoryResponse.from(it) }
        return ApiResponse.success(response)
    }
}
