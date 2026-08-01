package com.dongnemarket.auth.service

import com.dongnemarket.auth.dto.EmailVerificationConfirmRequest
import com.dongnemarket.auth.dto.EmailVerificationRequest
import com.dongnemarket.auth.mail.EmailSender
import com.dongnemarket.auth.repository.EmailVerificationCodeRepository
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.member.repository.MemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`

/**
 * `email` 이 `null` 로 들어왔을 때 **원본 Java 와 같은 지점·같은 예외 계열로 실패하는지** 고정한다.
 *
 * 왜 따로 두나 — Kotlin 전환 초안에서 메서드 진입 직후 `INVALID_INPUT_VALUE` 로 실패시키는 가드를 넣었다가
 * 실패 시점과 예외 종류가 함께 바뀌는 회귀를 만들었다. 정상 API 요청(`@NotBlank` 통과)에서는 도달하지
 * 않는 경로지만, **Java 에서 이 서비스를 직접 호출하는 런타임 계약**까지 보존해야 순수 언어 전환이다.
 *
 * 원본(`0a5af2d`) 의 null 경로:
 * - `requestVerification` — `existsByEmail(null)`(읽기) → `getRemainingTtl(null)` 에서 **NPE**.
 *   코드 저장·인증상태 무효화·메일 발송은 **하나도 실행되지 않는다.**
 * - `confirmVerification` — `existsByEmailAndVerifiedTrue(null)`(읽기) → `findCode(null)` 에서 **NPE**.
 *   코드 삭제·인증상태 반영·메일 발송은 **실행되지 않는다.**
 *
 * 실제 NPE 는 `InMemory...Repository` 의 `ConcurrentHashMap` 이 null 키를 거부하며 나므로,
 * 여기서는 그 지점을 모킹으로 재현한다.
 */
class EmailVerificationNullContractTest {
    private lateinit var codeRepository: EmailVerificationCodeRepository
    private lateinit var verificationRepository: EmailVerificationRepository
    private lateinit var memberRepository: MemberRepository
    private lateinit var emailSender: EmailSender
    private lateinit var service: EmailVerificationService

    @BeforeEach
    fun setUp() {
        codeRepository = mock(EmailVerificationCodeRepository::class.java)
        verificationRepository = mock(EmailVerificationRepository::class.java)
        memberRepository = mock(MemberRepository::class.java)
        emailSender = mock(EmailSender::class.java)
        service = EmailVerificationService(codeRepository, verificationRepository, memberRepository, emailSender)
    }

    @Nested
    @DisplayName("requestVerification — null email")
    inner class RequestVerification {
        @BeforeEach
        fun stubNullKeyRejection() {
            // 원본이 처음 실패하던 지점 재현: null 키가 ConcurrentHashMap 에 들어가며 NPE.
            `when`(codeRepository.getRemainingTtl(null)).thenThrow(NullPointerException())
        }

        @Test
        fun `원본과 같은 지점에서 NPE 계열로 실패한다`() {
            val thrown = runCatching { service.requestVerification(EmailVerificationRequest(null)) }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(NullPointerException::class.java)
        }

        /** 진입 가드로 바꿨을 때 생겼던 회귀 — 도메인 예외로 바뀌면 안 된다. */
        @Test
        fun `BusinessException 으로 바뀌지 않는다`() {
            val thrown = runCatching { service.requestVerification(EmailVerificationRequest(null)) }.exceptionOrNull()

            assertThat(thrown).isNotInstanceOf(BusinessException::class.java)
        }

        @Test
        fun `실패 전까지 원본이 하던 조회는 그대로 수행한다`() {
            runCatching { service.requestVerification(EmailVerificationRequest(null)) }

            verify(memberRepository).existsByEmail(null)
            verify(codeRepository).getRemainingTtl(null)
        }

        /** 원본은 이 지점 이후를 실행하지 않는다 — 코드 저장·상태 무효화가 없어야 한다. */
        @Test
        fun `코드 저장과 인증 상태 무효화가 일어나지 않는다`() {
            runCatching { service.requestVerification(EmailVerificationRequest(null)) }

            verify(codeRepository).getRemainingTtl(null)
            verifyNoMoreInteractions(codeRepository)
            verifyNoInteractions(verificationRepository)
        }

        @Test
        fun `메일이 발송되지 않는다`() {
            runCatching { service.requestVerification(EmailVerificationRequest(null)) }

            verifyNoInteractions(emailSender)
        }
    }

    @Nested
    @DisplayName("confirmVerification — null email")
    inner class ConfirmVerification {
        private fun call() =
            runCatching {
                service.confirmVerification(EmailVerificationConfirmRequest(null, "test-verification-code"))
            }.exceptionOrNull()

        @Test
        fun `NPE 계열로 실패하고 BusinessException 이 아니다`() {
            val thrown = call()

            assertThat(thrown).isInstanceOf(NullPointerException::class.java)
            assertThat(thrown).isNotInstanceOf(BusinessException::class.java)
        }

        /** 원본도 실패 전까지 쓰기 작업이 없다 — 코드 삭제·인증상태 저장이 일어나면 안 된다. */
        @Test
        fun `코드 삭제나 인증 상태 저장이 일어나지 않는다`() {
            call()

            verifyNoInteractions(codeRepository)
            verifyNoInteractions(emailSender)
        }
    }
}
