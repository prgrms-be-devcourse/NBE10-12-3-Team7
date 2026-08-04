package com.dongnemarket.member.entity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MemberTest {

    // ===== createAdmin =====

    @Test
    fun `createAdmin으로 생성한 회원은 ROLE_ADMIN, ACTIVE 상태다`() {
        val admin = Member.createAdmin("admin@example.com", "encoded-password", "관리자")

        assertThat(admin.email).isEqualTo("admin@example.com")
        assertThat(admin.nickname).isEqualTo("관리자")
        assertThat(admin.role).isEqualTo(Role.ROLE_ADMIN)
        assertThat(admin.status).isEqualTo(MemberStatus.ACTIVE)
        assertThat(admin.deletedAt).isNull()
    }

    // ===== changeStatus =====

    @Test
    fun `changeStatus(DELETED)는 status를 DELETED로 변경하고 deletedAt을 기록한다`() {
        val member = Member.createUser("user@example.com", "encoded-password", "tester")

        member.changeStatus(MemberStatus.DELETED)

        assertThat(member.status).isEqualTo(MemberStatus.DELETED)
        assertThat(member.deletedAt).isNotNull()
    }

    @Test
    fun `changeStatus(SUSPENDED)는 status를 SUSPENDED로 변경하고 deletedAt을 null로 초기화한다`() {
        val member = Member.createUser("user@example.com", "encoded-password", "tester")
        member.changeStatus(MemberStatus.DELETED)

        member.changeStatus(MemberStatus.SUSPENDED)

        assertThat(member.status).isEqualTo(MemberStatus.SUSPENDED)
        assertThat(member.deletedAt).isNull()
    }

    @Test
    fun `changeStatus(ACTIVE)는 status를 ACTIVE로 변경하고 deletedAt을 null로 초기화한다`() {
        val member = Member.createUser("user@example.com", "encoded-password", "tester")
        member.changeStatus(MemberStatus.DELETED)

        member.changeStatus(MemberStatus.ACTIVE)

        assertThat(member.status).isEqualTo(MemberStatus.ACTIVE)
        assertThat(member.deletedAt).isNull()
    }

    // ===== displayNickname =====

    @Test
    fun `DELETED 회원의 displayNickname은 실제 닉네임 대신 "탈퇴한 사용자"를 반환한다`() {
        val member = Member.createUser("user@example.com", "encoded-password", "tester")
        member.changeStatus(MemberStatus.DELETED)

        assertThat(member.displayNickname).isEqualTo("탈퇴한 사용자")
    }

    @Test
    fun `ACTIVE 또는 SUSPENDED 회원의 displayNickname은 실제 닉네임을 그대로 반환한다`() {
        val member = Member.createUser("user@example.com", "encoded-password", "tester")

        assertThat(member.displayNickname).isEqualTo("tester")

        member.changeStatus(MemberStatus.SUSPENDED)

        assertThat(member.displayNickname).isEqualTo("tester")
    }
}
