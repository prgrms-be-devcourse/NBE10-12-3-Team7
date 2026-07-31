package com.dongnemarket.manner.repository

import com.dongnemarket.manner.entity.MannerScore
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.util.Optional

interface MannerScoreRepository : JpaRepository<MannerScore, Long> {
    fun findByMember_Id(memberId: Long): Optional<MannerScore>

    /** 신고 목록 신뢰도 가중 정렬 등, 여러 회원의 온도를 한 번에 조회할 때 쓴다(N+1 방지). */
    fun findAllByMember_IdIn(memberIds: Collection<Long>): List<MannerScore>

    /** 관리자 저신뢰 회원 모니터링: 온도가 threshold 이하인 회원을 낮은 순으로 조회한다. */
    @Query("SELECT ms FROM MannerScore ms WHERE ms.score <= :threshold ORDER BY ms.score ASC")
    fun findAllByScoreLessThanEqualOrderByScoreAsc(
        @Param("threshold") threshold: BigDecimal,
    ): List<MannerScore>

    /** 회복 배치 대상: 아직 최대치(기본값)에 도달하지 못한 회원만 골라 불필요한 순회를 줄인다. */
    fun findAllByScoreLessThan(score: BigDecimal): List<MannerScore>
}
