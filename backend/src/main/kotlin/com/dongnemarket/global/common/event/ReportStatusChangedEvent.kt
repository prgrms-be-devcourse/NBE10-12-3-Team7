package com.dongnemarket.global.common.event

import com.dongnemarket.report.entity.ReportStatus

/**
 * 관리자가 신고 상태를 변경할 때 발행되는 도메인 이벤트.
 *
 * manner 도메인이 구독하여, 상태가 `COMPLETED`(정당한 신고 확정)로 바뀌면 피신고자의 매너온도를,
 * `REJECTED`(무고성 판정)로 바뀌면 신고자의 매너온도를 낮춘다. COMPLETED 확정이 누적되면
 * 계정 자동 정지 판단에도 쓰인다.
 *
 * Report 도메인은 이 이벤트를 발행만 할 뿐 누가 구독하는지 알지 못한다(단방향 의존, 결합도 최소화).
 *
 * `reportId` 는 **nullable 이어야 한다.** 조여보았다가 되돌린 자리다 —
 * `AdminReportServiceTest` 가 영속되지 않은 `Report`(id 가 null)로 `changeReportStatus` 를
 * 호출하므로, non-null 로 두면 발행 시점에 `IllegalStateException` 이 나 테스트가 깨진다.
 * 원본 Java record 의 박싱 `Long` 과 같은 폭을 유지한다.
 *
 * 구독자는 `findById` 가 non-null 을 요구하므로(`-Xjsr305=strict`) 읽는 쪽에서 언랩한다.
 *
 * @property reportId 상태가 바뀐 신고 id
 * @property newStatus 변경된 이후 상태
 */
data class ReportStatusChangedEvent(
    val reportId: Long?,
    val newStatus: ReportStatus,
)
