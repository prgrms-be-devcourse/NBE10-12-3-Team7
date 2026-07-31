package com.dongnemarket.manner.scheduler

import com.dongnemarket.manner.entity.MannerScore
import com.dongnemarket.manner.service.MannerScoreService
import com.dongnemarket.member.entity.Member
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.test.util.ReflectionTestUtils

/**
 * [단위] MannerScoreRecoveryScheduler — 회복 대상 필터링(감점 이력·거래 실적) 로직만 검증.
 *
 * MannerScoreService는 순수 Kotlin 클래스라 Mockito가 non-null 파라미터의 Kotlin @NotNull
 * 메타데이터를 감지해 `any()`/`any(Class)`를 방어적으로 거부한다 — [anyValid]로 우회한다
 * (mockito-kotlin 없이 쓰는 표준 패턴, MannerScoreEventListenerTest 참고).
 */
@ExtendWith(MockitoExtension::class)
class MannerScoreRecoverySchedulerTest {
    @Mock
    lateinit var mannerScoreService: MannerScoreService

    @InjectMocks
    lateinit var scheduler: MannerScoreRecoveryScheduler

    private fun candidate(memberId: Long): MannerScore {
        val member = Member.createUser("member-$memberId@example.com", "pw", "회원$memberId")
        ReflectionTestUtils.setField(member, "id", memberId)
        return MannerScore.createDefault(member)
    }

    @Nested
    @DisplayName("회복 대상 판단")
    inner class Eligibility {
        @Test
        fun `최근 30일 내 감점 이력이 있으면 회복을 적용하지 않는다`() {
            given(mannerScoreService.findRecoveryCandidates()).willReturn(listOf(candidate(1L)))
            given(mannerScoreService.hasPenaltySince(eq(1L), anyValid())).willReturn(true)

            scheduler.recoverEligibleMembers()

            verify(mannerScoreService, never()).applyTimeRecoveryTowardDefault(eq(1L), anyLong())
        }

        @Test
        fun `감점 이력은 없지만 최근 거래 완료 건수가 0이면 회복을 적용하지 않는다`() {
            given(mannerScoreService.findRecoveryCandidates()).willReturn(listOf(candidate(1L)))
            given(mannerScoreService.hasPenaltySince(eq(1L), anyValid())).willReturn(false)
            given(mannerScoreService.countCompletedTradesSince(eq(1L), anyValid())).willReturn(0L)

            scheduler.recoverEligibleMembers()

            verify(mannerScoreService, never()).applyTimeRecoveryTowardDefault(eq(1L), anyLong())
        }

        @Test
        fun `감점 이력이 없고 거래 완료 건수가 있으면 그 건수만큼 회복을 적용한다`() {
            given(mannerScoreService.findRecoveryCandidates()).willReturn(listOf(candidate(1L)))
            given(mannerScoreService.hasPenaltySince(eq(1L), anyValid())).willReturn(false)
            given(mannerScoreService.countCompletedTradesSince(eq(1L), anyValid())).willReturn(4L)

            scheduler.recoverEligibleMembers()

            verify(mannerScoreService).applyTimeRecoveryTowardDefault(1L, 4L)
        }

        @Test
        fun `대상이 여러 명이면 각자 독립적으로 판단한다`() {
            given(mannerScoreService.findRecoveryCandidates()).willReturn(listOf(candidate(1L), candidate(2L)))
            given(mannerScoreService.hasPenaltySince(eq(1L), anyValid())).willReturn(true)
            given(mannerScoreService.hasPenaltySince(eq(2L), anyValid())).willReturn(false)
            given(mannerScoreService.countCompletedTradesSince(eq(2L), anyValid())).willReturn(2L)

            scheduler.recoverEligibleMembers()

            verify(mannerScoreService, never()).applyTimeRecoveryTowardDefault(eq(1L), anyLong())
            verify(mannerScoreService).applyTimeRecoveryTowardDefault(2L, 2L)
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> anyValid(): T {
    ArgumentMatchers.any<T>()
    return null as T
}
