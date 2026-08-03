package com.dongnemarket.member.controller

import com.dongnemarket.global.response.ApiResponse
import com.dongnemarket.member.dto.MemberResponse
import com.dongnemarket.member.dto.MemberUpdateRequest
import com.dongnemarket.member.dto.PasswordChangeRequest
import com.dongnemarket.member.service.MemberService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 파라미터 nullability 는 AuthController 와 같은 규칙이다.
 * - `@AuthenticationPrincipal` 은 원본이 boxed `Long` 이고 principal 미존재 시 null 이 전달되므로 `Long?`.
 * - `@RequestBody` 는 non-null — nullable 로 두면 Spring 이 body 를 선택 사항으로 해석해
 *   원본의 "body 누락 → 400" 계약이 null 통과로 바뀐다.
 */
@Tag(name = "Member", description = "회원 API")
@RestController
@RequestMapping("/api/members")
class MemberController(
    private val memberService: MemberService,
) {
    @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자의 정보를 조회한다.")
    @GetMapping("/me")
    fun getMyInfo(
        @AuthenticationPrincipal memberId: Long?,
    ): ResponseEntity<ApiResponse<MemberResponse>> {
        val response = memberService.getMyInfo(memberId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(summary = "내 정보 수정", description = "현재 로그인한 사용자의 닉네임을 수정한다.")
    @PatchMapping("/me")
    fun updateMyInfo(
        @AuthenticationPrincipal memberId: Long?,
        @Valid @RequestBody request: MemberUpdateRequest,
    ): ResponseEntity<ApiResponse<MemberResponse>> {
        val response = memberService.updateMyInfo(memberId, request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(summary = "비밀번호 변경", description = "현재 로그인한 사용자의 비밀번호를 변경한다. 변경 성공 시 저장된 Refresh Token이 삭제되어 재로그인이 필요하다.")
    @PatchMapping("/me/password")
    fun changePassword(
        @AuthenticationPrincipal memberId: Long?,
        @Valid @RequestBody request: PasswordChangeRequest,
    ): ResponseEntity<ApiResponse<Void>> {
        memberService.changePassword(memberId, request)
        return ResponseEntity.ok(ApiResponse.success())
    }

    @Operation(summary = "회원 탈퇴", description = "현재 로그인한 사용자의 계정을 탈퇴 처리한다.")
    @DeleteMapping("/me")
    fun deleteMyInfo(
        @AuthenticationPrincipal memberId: Long?,
    ): ResponseEntity<ApiResponse<Void>> {
        memberService.deleteMyInfo(memberId)
        return ResponseEntity.ok(ApiResponse.success())
    }
}
