package com.dongnemarket.admin.dto

/** 관리자 신고 상태 변경 요청. 잘못된 값 검증은 AdminReportService 가 담당한다. */
data class AdminReportStatusUpdateRequest(
    val status: String?,
)
