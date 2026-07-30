package com.dongnemarket.admin.dto

/** 관리자 회원 상태 변경 요청. 잘못된 값 검증은 AdminMemberService 가 담당한다. */
data class AdminMemberStatusUpdateRequest(
    val status: String?,
)
