package com.dongnemarket.manner.entity

/** 매너온도 변화 사유. MannerScoreHistory에 기록되어 변화의 근거를 추적한다. */
enum class MannerScoreChangeReason {
    /** 구매자가 거래 후 남긴 별점 반영 */
    RATING_RECEIVED,

    /** 정상 거래(COMPLETED) 완료 보너스 */
    TRADE_COMPLETED,

    /** 신고가 정당(COMPLETED)하다고 확정되어 피신고자 온도 하락 */
    REPORT_CONFIRMED,

    /** 신고가 무고성(REJECTED)으로 판정되어 신고자 온도 하락 */
    FALSE_REPORT_PENALTY,

    /** 최근 거래 실적 기반 시간 경과 자동 회복 */
    TIME_RECOVERY,
}
