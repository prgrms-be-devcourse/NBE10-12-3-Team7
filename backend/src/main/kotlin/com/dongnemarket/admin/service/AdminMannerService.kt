package com.dongnemarket.admin.service

import com.dongnemarket.admin.dto.AdminMannerResponse
import com.dongnemarket.manner.entity.MannerScore
import com.dongnemarket.manner.service.MannerScoreService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
@Transactional(readOnly = true)
class AdminMannerService(
    private val mannerScoreService: MannerScoreService,
) {
    /**
     * threshold 가 없으면 기본 임계값을 쓴다.
     * Java 의 `threshold != null ? threshold : 기본값` 이 엘비스 연산자 한 번으로 대체된다.
     */
    fun getLowTrustMembers(threshold: BigDecimal?): List<AdminMannerResponse> =
        mannerScoreService
            .findLowTrustMembers(threshold ?: MannerScore.LOW_TRUST_THRESHOLD)
            .map(AdminMannerResponse::from)
}
