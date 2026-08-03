package com.dongnemarket.member.repository

import com.dongnemarket.member.entity.MemberLocation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** 파라미터 nullability 규칙은 [MemberRepository] 와 동일하다(원본 boxed `Long` 계약 유지). */
interface MemberLocationRepository : JpaRepository<MemberLocation, Long> {

    fun findAllByMemberIdOrderBySortOrderAsc(memberId: Long?): List<MemberLocation>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MemberLocation ml where ml.member.id = :memberId")
    fun deleteAllByMemberId(
        @Param("memberId") memberId: Long?,
    )
}
