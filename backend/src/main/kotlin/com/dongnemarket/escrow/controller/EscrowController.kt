package com.dongnemarket.escrow.controller

import com.dongnemarket.escrow.dto.EscrowCreateRequest
import com.dongnemarket.escrow.dto.EscrowResponse
import com.dongnemarket.escrow.service.EscrowService
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Escrow", description = "안심결제(에스크로) 거래 API")
@RestController
@RequestMapping("/api/escrows")
class EscrowController(
    private val escrowService: EscrowService,
) {
    /**
     * `memberId` 가 non-null 인 것은 인증이 필터에서 먼저 걸리기 때문이다 —
     * 토큰 없이 호출하면 컨트롤러에 닿기 전에 401 이다(EscrowControllerTest E7 이 검증).
     * 이미 Kotlin 인 FavoriteController·NotificationController 도 같은 형태다.
     */
    @Operation(summary = "거래 시작", description = "구매자가 안심결제로 거래를 시작하고 대금을 예치합니다.")
    @PostMapping
    fun createEscrow(
        @AuthenticationPrincipal memberId: Long,
        @Valid @RequestBody request: EscrowCreateRequest,
    ): ResponseEntity<ApiResponse<EscrowResponse>> {
        val response = escrowService.create(memberId, request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(HttpStatus.CREATED.value(), "거래가 시작되었습니다.", response))
    }

    @Operation(summary = "거래 조회", description = "거래 상세를 조회합니다.")
    @GetMapping("/{escrowId}")
    fun getEscrow(
        @PathVariable escrowId: Long,
    ): ApiResponse<EscrowResponse> = ApiResponse.success(escrowService.get(escrowId))

    @Operation(
        summary = "구매확정",
        description = "구매자가 물건을 확인하고 거래를 확정합니다. 대금이 판매자에게 정산(가정)됩니다.",
    )
    @PostMapping("/{escrowId}/confirm")
    fun confirmEscrow(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable escrowId: Long,
    ): ApiResponse<EscrowResponse> = ApiResponse.success("구매가 확정되었습니다.", escrowService.confirm(memberId, escrowId))

    @Operation(summary = "거래 취소", description = "구매확정 전 거래를 취소하고 환불(가정)합니다.")
    @PostMapping("/{escrowId}/cancel")
    fun cancelEscrow(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable escrowId: Long,
    ): ApiResponse<EscrowResponse> = ApiResponse.success("거래가 취소되었습니다.", escrowService.cancel(memberId, escrowId))
}
