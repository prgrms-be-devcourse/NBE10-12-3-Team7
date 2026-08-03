package com.dongnemarket.member.entity

/**
 * 회원 권한. JwtTokenProvider 에 전달될 때는 `name()` 그대로 ("ROLE_USER"/"ROLE_ADMIN") 사용한다.
 */
enum class Role {
    ROLE_USER,
    ROLE_ADMIN,
}
