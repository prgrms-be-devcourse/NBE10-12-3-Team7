package com.dongnemarket.member.repository

import com.dongnemarket.member.entity.Member
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * 파라미터가 nullable 인 이유: 원본 Java 참조형 파라미터의 계약을 그대로 유지한다
 * (auth 저장소 전환과 같은 규칙 — null 이 들어오면 조여서 NPE 를 내는 게 아니라
 * 쿼리에 그대로 흘러 "일치 없음"으로 동작한다). 호출부(AuthService 등)도 nullable 값을
 * `!!` 없이 전달한다.
 */
interface MemberRepository : JpaRepository<Member, Long> {
    fun existsByEmail(email: String?): Boolean

    fun existsByNickname(nickname: String?): Boolean

    fun existsByNicknameAndIdNot(
        nickname: String?,
        id: Long?,
    ): Boolean

    fun findByEmail(email: String?): Optional<Member>
}
