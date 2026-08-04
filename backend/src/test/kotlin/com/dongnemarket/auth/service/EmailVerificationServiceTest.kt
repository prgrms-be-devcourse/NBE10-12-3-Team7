package com.dongnemarket.auth.service

import com.dongnemarket.auth.dto.EmailVerificationConfirmRequest
import com.dongnemarket.auth.dto.EmailVerificationRequest
import com.dongnemarket.auth.entity.EmailVerification
import com.dongnemarket.auth.mail.EmailSender
import com.dongnemarket.auth.repository.EmailVerificationCodeRepository
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.auth.repository.InMemoryEmailVerificationCodeRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.repository.MemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Duration
import java.time.LocalDateTime
import java.util.Optional

/**
 * 코드 저장소는 실제 [InMemoryEmailVerificationCodeRepository](TTL 흉내)를 사용하고,
 * "인증 완료" DB 기록([EmailVerificationRepository])과 회원가입 여부·발송만 Mock 처리한다.
 */
@ExtendWith(MockitoExtension::class)
class EmailVerificationServiceTest {
    @Mock
    lateinit var emailVerificationRepository: EmailVerificationRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var emailSender: EmailSender

    lateinit var codeRepository: EmailVerificationCodeRepository
    lateinit var emailVerificationService: EmailVerificationService

    @BeforeEach
    fun setUp() {
        codeRepository = InMemoryEmailVerificationCodeRepository()
        emailVerificationService =
            EmailVerificationService(codeRepository, emailVerificationRepository, memberRepository, emailSender)
    }

    // ===== requestVerification =====

    @Test
    @DisplayName("가입되지 않은 이메일이고 기존 코드가 없으면 코드를 Redis(코드 저장소)에 저장하고 발송한다")
    fun requestVerification_newEmail_success() {
        val request = EmailVerificationRequest("new@example.com")
        given(memberRepository.existsByEmail("new@example.com")).willReturn(false)

        val response = emailVerificationService.requestVerification(request)

        assertThat(response.email).isEqualTo("new@example.com")
        assertThat(response.expiresAt).isAfter(LocalDateTime.now())
        assertThat(codeRepository.findCode("new@example.com")).isPresent()
        verify(emailSender).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 DUPLICATE_EMAIL 예외가 발생하고 발송하지 않는다")
    fun requestVerification_alreadyRegisteredEmail_throwsException() {
        val request = EmailVerificationRequest("taken@example.com")
        given(memberRepository.existsByEmail("taken@example.com")).willReturn(true)

        assertThatThrownBy { emailVerificationService.requestVerification(request) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_EMAIL)

        assertThat(codeRepository.findCode("taken@example.com")).isEmpty()
        verify(emailSender, never()).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("60초 이내에 재요청하면(남은 TTL이 240초 초과) EMAIL_VERIFICATION_REQUEST_TOO_SOON 예외가 발생한다")
    fun requestVerification_withinCooldown_throwsException() {
        val request = EmailVerificationRequest("cooldown@example.com")
        given(memberRepository.existsByEmail("cooldown@example.com")).willReturn(false)
        // 10초 전 발급을 흉내: 5분 TTL 중 295초(4분55초) 남음 = 240초 초과이므로 쿨다운 중
        codeRepository.save("cooldown@example.com", "111111", Duration.ofSeconds(295))

        assertThatThrownBy { emailVerificationService.requestVerification(request) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_VERIFICATION_REQUEST_TOO_SOON)

        verify(emailSender, never()).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("쿨다운이 지난 뒤(남은 TTL이 240초 이하) 재요청하면 기존 코드를 교체하고 재발송한다")
    fun requestVerification_afterCooldown_replacesAndResends() {
        val request = EmailVerificationRequest("resend@example.com")
        given(memberRepository.existsByEmail("resend@example.com")).willReturn(false)
        given(emailVerificationRepository.findByEmail("resend@example.com")).willReturn(Optional.empty())
        // 61초 전 발급을 흉내: 5분 TTL 중 239초 남음 = 240초 이하이므로 쿨다운 지남
        codeRepository.save("resend@example.com", "111111", Duration.ofSeconds(239))

        emailVerificationService.requestVerification(request)

        assertThat(codeRepository.findCode("resend@example.com")).isPresent().get().isNotEqualTo("111111")
        verify(emailSender).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("이미 인증 완료된 이메일이 새 코드를 재요청하면 DB의 인증 완료 상태를 무효화한다")
    fun requestVerification_afterCooldown_unverifiesExistingRecord() {
        val request = EmailVerificationRequest("reverify@example.com")
        given(memberRepository.existsByEmail("reverify@example.com")).willReturn(false)
        val existing = EmailVerification.verified("reverify@example.com", LocalDateTime.now().minusMinutes(20))
        given(emailVerificationRepository.findByEmail("reverify@example.com")).willReturn(Optional.of(existing))

        emailVerificationService.requestVerification(request)

        assertThat(existing.verified).isFalse()
        assertThat(existing.verifiedAt).isNull()
    }

    // ===== confirmVerification =====

    @Test
    @DisplayName("코드가 일치하면 코드 저장소에서 삭제하고 DB에 인증 완료 기록을 새로 저장한다")
    fun confirmVerification_correctCode_success() {
        codeRepository.save("confirm@example.com", "123456", Duration.ofMinutes(5))
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue("confirm@example.com")).willReturn(false)
        given(emailVerificationRepository.findByEmail("confirm@example.com")).willReturn(Optional.empty())

        val response =
            emailVerificationService.confirmVerification(
                EmailVerificationConfirmRequest("confirm@example.com", "123456"),
            )

        assertThat(response.email).isEqualTo("confirm@example.com")
        assertThat(response.verified).isTrue()
        assertThat(codeRepository.findCode("confirm@example.com")).isEmpty()
        val captor: ArgumentCaptor<EmailVerification> = ArgumentCaptor.forClass(EmailVerification::class.java)
        verify(emailVerificationRepository).save(captor.capture())
        assertThat(captor.value.verified).isTrue()
    }

    @Test
    @DisplayName("코드가 일치하지 않으면 INVALID_VERIFICATION_CODE 예외가 발생하고 코드는 삭제되지 않는다")
    fun confirmVerification_wrongCode_throwsException() {
        codeRepository.save("confirm@example.com", "123456", Duration.ofMinutes(5))
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue("confirm@example.com")).willReturn(false)

        assertThatThrownBy {
            emailVerificationService.confirmVerification(
                EmailVerificationConfirmRequest("confirm@example.com", "000000"),
            )
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_VERIFICATION_CODE)

        assertThat(codeRepository.findCode("confirm@example.com")).contains("123456")
    }

    @Test
    @DisplayName("인증 요청 이력이 없거나(또는 TTL 만료로 코드가 사라졌으면) EMAIL_VERIFICATION_NOT_FOUND 예외가 발생한다")
    fun confirmVerification_noCodeFound_throwsException() {
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue("unknown@example.com")).willReturn(false)

        assertThatThrownBy {
            emailVerificationService.confirmVerification(
                EmailVerificationConfirmRequest("unknown@example.com", "123456"),
            )
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_VERIFICATION_NOT_FOUND)
    }

    @Test
    @DisplayName("이미 인증 완료된 건이면 코드 검사 없이 다시 성공을 반환한다(멱등)")
    fun confirmVerification_alreadyVerified_returnsSuccessIdempotently() {
        given(emailVerificationRepository.existsByEmailAndVerifiedTrue("confirm@example.com")).willReturn(true)

        val response =
            emailVerificationService.confirmVerification(
                EmailVerificationConfirmRequest("confirm@example.com", "wrong-code"),
            )

        assertThat(response.verified).isTrue()
    }
}
