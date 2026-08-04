package com.dongnemarket.manner.scheduler

import com.dongnemarket.manner.service.MannerScoreService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 매너온도 시간 경과 자동 회복 배치. 매일 새벽 실행되어, 기본값(36.5) 미만인 회원 중
 * 최근 30일간 신고 확정/무고성 페널티(감점 이력)가 없는 회원만 골라, 같은 기간 정상 거래
 * 완료 건수에 비례해 온도를 회복시킨다. 단순 "시간이 지나면 회복"이 아니라 "최근에도
 * 실제로 문제없이 거래했는가"를 반영한 회복이다.
 */
@Component
class MannerScoreRecoveryScheduler(
    private val mannerScoreService: MannerScoreService,
) {
    /** 매일 새벽 3시 실행 (cron: 초 분 시 일 월 요일) */
    @Scheduled(cron = "0 0 3 * * *")
    fun recoverEligibleMembers() {
        val since = LocalDateTime.now().minusDays(LOOKBACK_DAYS)
        val candidates = mannerScoreService.findRecoveryCandidates()

        var recovered = 0
        for (mannerScore in candidates) {
            val memberId = mannerScore.member.id!!
            if (mannerScoreService.hasPenaltySince(memberId, since)) {
                continue // 최근 30일 내 감점 이력이 있으면 이번 배치는 건너뛴다
            }
            val tradeCount = mannerScoreService.countCompletedTradesSince(memberId, since)
            if (tradeCount <= 0) {
                continue
            }
            mannerScoreService.applyTimeRecoveryTowardDefault(memberId, tradeCount)
            recovered++
        }
        log.info("매너온도 회복 배치 완료: 대상 {}명 중 {}명 회복 적용", candidates.size, recovered)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MannerScoreRecoveryScheduler::class.java)
        private const val LOOKBACK_DAYS = 30L
    }
}
