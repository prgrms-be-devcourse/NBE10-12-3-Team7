package com.dongnemarket.admin.repository

import com.dongnemarket.member.entity.Member
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Admin 전용 회원 조회 Repository.
 * 팀원(member) Repository를 수정하지 않기 위해 admin 패키지에 별도로 둔다.
 * 관리자는 상태(ACTIVE/SUSPENDED/DELETED)와 무관하게 전체 회원을 조회한다.
 */
interface AdminMemberRepository : JpaRepository<Member, Long>
