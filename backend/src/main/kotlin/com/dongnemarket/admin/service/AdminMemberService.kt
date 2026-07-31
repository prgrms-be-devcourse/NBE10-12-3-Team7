package com.dongnemarket.admin.service

import com.dongnemarket.admin.dto.AdminMemberResponse
import com.dongnemarket.admin.dto.AdminMemberStatusUpdateRequest
import com.dongnemarket.admin.repository.AdminMemberRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.entity.MemberStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AdminMemberService(
    private val adminMemberRepository: AdminMemberRepository,
) {
    /** 전체 회원 목록 (상태 무관) */
    fun getMembers(): List<AdminMemberResponse> = adminMemberRepository.findAll().map(AdminMemberResponse::from)

    /** 회원 단건 상세 */
    fun getMember(memberId: Long): AdminMemberResponse {
        val member =
            adminMemberRepository
                .findById(memberId)
                .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        return AdminMemberResponse.from(member)
    }

    /**
     * 회원 상태 변경 (ACTIVE/SUSPENDED/DELETED).
     *
     * `request` 가 nullable 인 것은 원본 Java 시그니처를 그대로 옮긴 결과다 —
     * AdminMemberServiceTest 가 request 자체에 null 을 넘겨 INVALID_MEMBER_STATUS 방어를 검증한다.
     * non-null 로 조이면 Kotlin 이 삽입하는 null 검사에 먼저 걸려 예외 종류가 달라진다.
     */
    @Transactional
    fun changeMemberStatus(
        memberId: Long,
        request: AdminMemberStatusUpdateRequest?,
    ): AdminMemberResponse {
        val member =
            adminMemberRepository
                .findById(memberId)
                .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        member.changeStatus(parseStatus(request))
        return AdminMemberResponse.from(member)
    }

    /**
     * Java 의 `request == null || getStatus() == null || getStatus().isBlank()` 세 조건이
     * 안전 호출 + isNullOrBlank() 두 줄로 합쳐진다. 검사를 통과하면 스마트 캐스트로
     * 아래에서 status 를 non-null 로 쓸 수 있다.
     */
    private fun parseStatus(request: AdminMemberStatusUpdateRequest?): MemberStatus {
        val status = request?.status
        if (status.isNullOrBlank()) {
            throw BusinessException(ErrorCode.INVALID_MEMBER_STATUS)
        }
        return try {
            MemberStatus.valueOf(status.trim())
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.INVALID_MEMBER_STATUS)
        }
    }
}
