package com.dongnemarket.region.service

import com.dongnemarket.region.dto.RegionResponse
import com.dongnemarket.region.repository.RegionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils

@Service
@Transactional(readOnly = true)
class RegionService(
    private val regionRepository: RegionRepository,
) {
    /**
     * `parentCode` 가 없으면 최상위 지역(시·도), 있으면 그 아래 하위 지역을 조회한다.
     *
     * 원본 Java 는 무인자 오버로드를 따로 뒀다. 기본 인자 + `@JvmOverloads` 로 옮겨
     * Java 에서 보이는 시그니처 2개를 그대로 유지한다.
     */
    @JvmOverloads
    fun getRegions(parentCode: String? = null): List<RegionResponse> =
        if (!StringUtils.hasText(parentCode)) {
            regionRepository.findAllByParentIsNullOrderByDisplayNameAsc().map(RegionResponse::from)
        } else {
            regionRepository.findAllByParentCodeOrderByDisplayNameAsc(parentCode!!).map(RegionResponse::from)
        }
}
