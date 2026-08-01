package com.dongnemarket.manner.service

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.manner.entity.MannerScore
import com.dongnemarket.manner.entity.MannerScoreChangeReason
import com.dongnemarket.manner.entity.MannerScoreHistory
import com.dongnemarket.manner.repository.MannerScoreHistoryRepository
import com.dongnemarket.manner.repository.MannerScoreRepository
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.util.Optional

/** [단위] MannerScoreService — 온도 조정([adjust]를 거치는 모든 공개 메서드)과 조회 위임을 검증한다. */
@ExtendWith(MockitoExtension::class)
class MannerScoreServiceTest {
    @Mock
    lateinit var mannerScoreRepository: MannerScoreRepository

    @Mock
    lateinit var mannerScoreHistoryRepository: MannerScoreHistoryRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    @InjectMocks
    lateinit var mannerScoreService: MannerScoreService

    private fun member(id: Long): Member {
        val member = Member.createUser("member-$id@example.com", "pw", "회원$id")
        ReflectionTestUtils.setField(member, "id", id)
        return member
    }

    private fun mannerScore(
        memberId: Long,
        score: BigDecimal,
    ): MannerScore {
        val mannerScore = MannerScore.createDefault(member(memberId))
        ReflectionTestUtils.setField(mannerScore, "score", score)
        return mannerScore
    }

    @Nested
    @DisplayName("getOrCreate")
    inner class GetOrCreate {
        @Test
        fun `이미 매너온도가 있으면 그대로 반환하고 새로 저장하지 않는다`() {
            val existing = mannerScore(1L, BigDecimal.valueOf(50.0))
            given(mannerScoreRepository.findByMember_Id(1L)).willReturn(Optional.of(existing))

            val result = mannerScoreService.getOrCreate(1L)

            assertThat(result).isSameAs(existing)
            verify(mannerScoreRepository, never()).save(any())
        }

        @Test
        fun `매너온도가 없으면 기본값(36_5)으로 생성해 저장한다`() {
            val member = member(1L)
            given(mannerScoreRepository.findByMember_Id(1L)).willReturn(Optional.empty())
            given(memberRepository.findById(1L)).willReturn(Optional.of(member))
            given(mannerScoreRepository.save(any(MannerScore::class.java))).willAnswer { it.arguments[0] }

            val result = mannerScoreService.getOrCreate(1L)

            assertThat(result.score).isEqualByComparingTo(MannerScore.DEFAULT_SCORE)
        }

        @Test
        fun `회원 자체가 없으면 MEMBER_NOT_FOUND 예외가 발생한다`() {
            given(mannerScoreRepository.findByMember_Id(1L)).willReturn(Optional.empty())
            given(memberRepository.findById(1L)).willReturn(Optional.empty())

            val ex = assertThrows<BusinessException> { mannerScoreService.getOrCreate(1L) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.MEMBER_NOT_FOUND)
        }
    }

    @Nested
    @DisplayName("온도를 조정하는 공개 메서드들")
    inner class AdjustMethods {
        @Test
        fun `별점 5점을 반영하면 0_2 상승하고 RATING_RECEIVED 이력이 남는다`() {
            val score = mannerScore(1L, BigDecimal.valueOf(50.0))
            given(mannerScoreRepository.findByMember_Id(1L)).willReturn(Optional.of(score))

            mannerScoreService.applyRating(1L, 5)

            assertThat(score.score).isEqualByComparingTo("50.2")
            val captor = ArgumentCaptor.forClass(MannerScoreHistory::class.java)
            verify(mannerScoreHistoryRepository).save(captor.capture())
            assertThat(captor.value.changeAmount).isEqualByComparingTo("0.2")
            assertThat(captor.value.reason).isEqualTo(MannerScoreChangeReason.RATING_RECEIVED)
        }

        @Test
        fun `별점 3점(보통)을 반영하면 변화가 없어 이력도 남기지 않는다`() {
            val score = mannerScore(1L, BigDecimal.valueOf(50.0))
            given(mannerScoreRepository.findByMember_Id(1L)).willReturn(Optional.of(score))

            mannerScoreService.applyRating(1L, 3)

            assertThat(score.score).isEqualByComparingTo("50.0")
            verify(mannerScoreHistoryRepository, never()).save(any())
        }

        @Test
        fun `거래 완료를 반영하면 0_1 상승한다`() {
            val score = mannerScore(1L, BigDecimal.valueOf(50.0))
            given(mannerScoreRepository.findByMember_Id(1L)).willReturn(Optional.of(score))

            mannerScoreService.applyTradeCompleted(1L)

            assertThat(score.score).isEqualByComparingTo("50.1")
        }

        @Test
        fun `신고 확정을 반영하면 심각도만큼 무조건 하락한다`() {
            val score = mannerScore(1L, BigDecimal.valueOf(50.0))
            given(mannerScoreRepository.findByMember_Id(1L)).willReturn(Optional.of(score))

            mannerScoreService.applyReportConfirmed(1L, BigDecimal.valueOf(-1.0), 100L)

            assertThat(score.score).isEqualByComparingTo("49.0")
        }

        @Test
        fun `무고성 페널티를 반영하면 0_3 하락한다`() {
            val score = mannerScore(1L, BigDecimal.valueOf(50.0))
            given(mannerScoreRepository.findByMember_Id(1L)).willReturn(Optional.of(score))

            mannerScoreService.applyFalseReportPenalty(1L, 100L)

            assertThat(score.score).isEqualByComparingTo("49.7")
        }

        @Test
        fun `이미 최저치(0_0)라 실제 변화가 없으면 이력을 남기지 않는다`() {
            val score = mannerScore(1L, MannerScore.MIN_SCORE)
            given(mannerScoreRepository.findByMember_Id(1L)).willReturn(Optional.of(score))

            mannerScoreService.applyFalseReportPenalty(1L, 100L)

            assertThat(score.score).isEqualByComparingTo(MannerScore.MIN_SCORE)
            verify(mannerScoreHistoryRepository, never()).save(any())
        }
    }

    @Nested
    @DisplayName("applyTimeRecoveryTowardDefault")
    inner class TimeRecovery {
        @Test
        fun `거래 건수가 0 이하면 아무 것도 하지 않는다`() {
            mannerScoreService.applyTimeRecoveryTowardDefault(1L, 0)

            verify(mannerScoreRepository, never()).findByMember_Id(anyLong())
        }

        @Test
        fun `이미 기본값 이상이면 회복시키지 않는다`() {
            val score = mannerScore(1L, MannerScore.DEFAULT_SCORE.add(BigDecimal.ONE))
            given(mannerScoreRepository.findByMember_Id(1L)).willReturn(Optional.of(score))

            mannerScoreService.applyTimeRecoveryTowardDefault(1L, 5)

            verify(mannerScoreHistoryRepository, never()).save(any())
        }

        @Test
        fun `회복량은 기본값을 넘지 않도록 캡을 건다`() {
            // 기본값(36.5)에서 0.05만 모자란 상태 + 거래 10건(요청량 1.0)이면, 실제로는 0.05만 회복돼야 한다.
            val score = mannerScore(1L, MannerScore.DEFAULT_SCORE.subtract(BigDecimal("0.05")))
            given(mannerScoreRepository.findByMember_Id(1L)).willReturn(Optional.of(score))

            mannerScoreService.applyTimeRecoveryTowardDefault(1L, 10)

            assertThat(score.score).isEqualByComparingTo(MannerScore.DEFAULT_SCORE)
        }
    }

    @Nested
    @DisplayName("severityOf")
    inner class SeverityOf {
        @Test
        fun `FAKE_ITEM과 FRAUD_SUSPECTED는 가장 심각한 하락폭을 반환한다`() {
            assertThat(mannerScoreService.severityOf("FAKE_ITEM")).isEqualByComparingTo("-1.0")
            assertThat(mannerScoreService.severityOf("FRAUD_SUSPECTED")).isEqualByComparingTo("-1.0")
        }

        @Test
        fun `PROHIBITED_ITEM과 INAPPROPRIATE_CONTENT는 중간 하락폭을 반환한다`() {
            assertThat(mannerScoreService.severityOf("PROHIBITED_ITEM")).isEqualByComparingTo("-0.5")
            assertThat(mannerScoreService.severityOf("INAPPROPRIATE_CONTENT")).isEqualByComparingTo("-0.5")
        }

        @Test
        fun `그 외 사유(ETC 등)는 가장 낮은 하락폭을 반환한다`() {
            assertThat(mannerScoreService.severityOf("ETC")).isEqualByComparingTo("-0.3")
        }
    }

    @Nested
    @DisplayName("조회 위임")
    inner class Queries {
        @Test
        fun `getScoresByMemberIds는 레코드가 없는 회원을 기본값으로 채운다`() {
            val existing = mannerScore(1L, BigDecimal.valueOf(70.0))
            given(mannerScoreRepository.findAllByMember_IdIn(setOf(1L, 2L))).willReturn(listOf(existing))

            val scores = mannerScoreService.getScoresByMemberIds(setOf(1L, 2L))

            assertThat(scores[1L]).isEqualByComparingTo("70.0")
            assertThat(scores[2L]).isEqualByComparingTo(MannerScore.DEFAULT_SCORE)
        }

        @Test
        fun `suspend는 회원 상태를 SUSPENDED로 바꾼다`() {
            val member = member(1L)
            given(memberRepository.findById(1L)).willReturn(Optional.of(member))

            mannerScoreService.suspend(1L)

            assertThat(member.status).isEqualTo(MemberStatus.SUSPENDED)
        }

        @Test
        fun `suspend 대상 회원이 없으면 MEMBER_NOT_FOUND 예외가 발생한다`() {
            given(memberRepository.findById(1L)).willReturn(Optional.empty())

            val ex = assertThrows<BusinessException> { mannerScoreService.suspend(1L) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.MEMBER_NOT_FOUND)
        }
    }
}
