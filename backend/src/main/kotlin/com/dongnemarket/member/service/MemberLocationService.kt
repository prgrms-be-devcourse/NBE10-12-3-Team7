package com.dongnemarket.member.service

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.dto.MemberLocationResponse
import com.dongnemarket.member.dto.MemberLocationUpdateRequest
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberLocation
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberLocationRepository
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils

/** public 메서드 파라미터 nullability 규칙은 [MemberService] 와 동일하다(원본 boxed `Long`·참조형 계약 유지). */
@Service
@Transactional(readOnly = true)
class MemberLocationService(
    private val memberRepository: MemberRepository,
    private val memberLocationRepository: MemberLocationRepository,
    private val regionRepository: RegionRepository,
) {
    @Transactional
    fun updateMyLocations(
        memberId: Long?,
        request: MemberLocationUpdateRequest?,
    ): List<MemberLocationResponse> {
        val member = getActiveMember(memberId)
        val regions = getRequiredDongRegions(request!!.regionCodes)

        memberLocationRepository.deleteAllByMemberId(memberId)
        val memberLocations = ArrayList<MemberLocation>()
        for (i in regions.indices) {
            memberLocations.add(MemberLocation.create(member, regions[i], i, i == 0))
        }

        return memberLocationRepository
            .saveAll(memberLocations)
            .map { MemberLocationResponse.from(it) }
    }

    fun getMyLocations(memberId: Long?): List<MemberLocationResponse> {
        getActiveMember(memberId)
        return memberLocationRepository
            .findAllByMemberIdOrderBySortOrderAsc(memberId)
            .map { MemberLocationResponse.from(it) }
    }

    private fun getActiveMember(memberId: Long?): Member {
        val member =
            memberRepository
                .findById(requireNotNull(memberId))
                .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        validateActiveMember(member)
        return member
    }

    private fun validateActiveMember(member: Member) {
        if (member.status == MemberStatus.DELETED) {
            throw BusinessException(ErrorCode.DELETED_MEMBER)
        }
        if (member.status == MemberStatus.SUSPENDED) {
            throw BusinessException(ErrorCode.SUSPENDED_MEMBER)
        }
    }

    private fun getRequiredDongRegions(regionCodes: List<String>?): List<Region> {
        if (regionCodes == null || regionCodes.isEmpty()) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }

        val uniqueRegionCodes = HashSet(regionCodes)
        if (uniqueRegionCodes.size != regionCodes.size) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }

        val regions = ArrayList<Region>()
        for (regionCode in regionCodes) {
            // 원소 null 은 원본(Java List 의 null 원소)과 동일하게 hasText 가 걸러낸다.
            @Suppress("SENSELESS_COMPARISON")
            if (regionCode == null || !StringUtils.hasText(regionCode)) {
                throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
            }
            val region =
                regionRepository
                    .findByCode(regionCode)
                    .orElseThrow { BusinessException(ErrorCode.INVALID_INPUT_VALUE) }
            if (region.level != 3) {
                throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
            }
            regions.add(region)
        }
        return regions
    }
}
