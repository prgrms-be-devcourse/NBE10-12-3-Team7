package com.dongnemarket.member.entity

/**
 * 회원 상태. SUSPENDED/DELETED 회원은 로그인할 수 없다 (00-ai-common-rules.md §8).
 */
enum class MemberStatus {
    ACTIVE,
    SUSPENDED,
    DELETED,
}
