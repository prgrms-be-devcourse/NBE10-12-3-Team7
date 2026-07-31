package com.dongnemarket.admin.ai.tool

import com.dongnemarket.admin.dto.AdminMemberResponse
import com.dongnemarket.admin.service.AdminMemberService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * 회원 조회 Tool (읽기 전용).
 * LLM이 회원 관련 질문을 받으면 이 메서드들을 호출한다.
 * 비즈니스 로직은 두지 않고 기존 AdminMemberService에 위임만 한다.
 */
@Component
class AdminMemberTools(
    private val adminMemberService: AdminMemberService,
) {
    @Tool(
        description =
            "전체 회원 목록을 조회한다. 상태(ACTIVE/SUSPENDED/DELETED)와 무관하게 모든 회원이 반환된다. " +
                "정지된 회원, 탈퇴한 회원 등 특정 상태만 필요하면 이 목록에서 status 값으로 걸러서 답하라.",
    )
    fun listMembers(): List<AdminMemberResponse> = adminMemberService.getMembers()

    @Tool(description = "회원 ID(숫자)로 회원 한 명의 상세 정보를 조회한다.")
    fun getMember(
        @ToolParam(description = "조회할 회원의 ID (양의 정수)") memberId: Long,
    ): AdminMemberResponse = adminMemberService.getMember(memberId)
}
