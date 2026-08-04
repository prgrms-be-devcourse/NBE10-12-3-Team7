package com.dongnemarket.member.service

import com.dongnemarket.auth.service.RefreshTokenService
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.dto.MemberResponse
import com.dongnemarket.member.dto.MemberUpdateRequest
import com.dongnemarket.member.dto.PasswordChangeRequest
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * public 메서드 파라미터가 nullable 인 이유: 원본 Java 시그니처가 boxed `Long`·참조형이라
 * 계약을 그대로 유지한다(AuthService 와 같은 규칙). `request!!` 는 원본이 첫 역참조에서
 * NPE 를 내던 지점과 같은 위치에 둔다.
 */
@Service
@Transactional(readOnly = true)
class MemberService(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val refreshTokenService: RefreshTokenService,
) {
    fun getMyInfo(memberId: Long?): MemberResponse {
        val member =
            memberRepository
                .findById(requireNotNull(memberId))
                .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        validateActiveMember(member)
        return MemberResponse.from(member)
    }

    @Transactional
    fun updateMyInfo(
        memberId: Long?,
        request: MemberUpdateRequest?,
    ): MemberResponse {
        val member =
            memberRepository
                .findById(requireNotNull(memberId))
                .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        validateActiveMember(member)

        if (memberRepository.existsByNicknameAndIdNot(request!!.nickname, memberId)) {
            throw BusinessException(ErrorCode.DUPLICATE_NICKNAME)
        }

        member.update(request.nickname!!)
        return MemberResponse.from(member)
    }

    /** 비밀번호 변경 성공 시 저장된 Refresh Token을 삭제해 다른 세션에서도 재로그인을 유도한다(단일 세션 정책이라 사실상 모든 세션이 갱신됨). */
    @Transactional
    fun changePassword(
        memberId: Long?,
        request: PasswordChangeRequest?,
    ) {
        val member =
            memberRepository
                .findById(requireNotNull(memberId))
                .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        validateActiveMember(member)
        if (!member.isLocalLoginEnabled) {
            throw BusinessException(ErrorCode.SOCIAL_ONLY_ACCOUNT_PASSWORD_CHANGE)
        }

        if (!passwordEncoder.matches(request!!.currentPassword, member.password)) {
            throw BusinessException(ErrorCode.INVALID_PASSWORD)
        }
        if (passwordEncoder.matches(request.newPassword, member.password)) {
            throw BusinessException(ErrorCode.SAME_AS_OLD_PASSWORD)
        }

        member.changePassword(passwordEncoder.encode(request.newPassword))
        refreshTokenService.deleteByMemberId(memberId)
    }

    @Transactional
    fun deleteMyInfo(memberId: Long?) {
        val member =
            memberRepository
                .findById(requireNotNull(memberId))
                .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        validateActiveMember(member)
        member.softDelete()
    }

    private fun validateActiveMember(member: Member) {
        if (member.status == MemberStatus.DELETED) {
            throw BusinessException(ErrorCode.DELETED_MEMBER)
        }
        if (member.status == MemberStatus.SUSPENDED) {
            throw BusinessException(ErrorCode.SUSPENDED_MEMBER)
        }
    }
}
