package com.dongnemarket.auth.mail

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.willThrow
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

@ExtendWith(MockitoExtension::class)
class SmtpEmailSenderTest {
    @Mock
    lateinit var javaMailSender: JavaMailSender

    lateinit var emailSender: SmtpEmailSender

    @Test
    @DisplayName("발송에 성공하면 발신자 표시 이름 포함 발신자·수신자·제목·본문이 담긴 메일을 JavaMailSender로 전달한다")
    fun send_success() {
        emailSender = SmtpEmailSender(javaMailSender, "noreply@example.com")

        emailSender.send("test@example.com", "인증 코드", "코드: 123456")

        val captor = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        verify(javaMailSender).send(captor.capture())
        val sent = captor.value
        assertThat(sent.from).isEqualTo("마켓온 <noreply@example.com>")
        assertThat(sent.to).containsExactly("test@example.com")
        assertThat(sent.subject).isEqualTo("인증 코드")
        assertThat(sent.text).isEqualTo("코드: 123456")
    }

    @Test
    @DisplayName("JavaMailSender가 발송에 실패하면 EMAIL_SEND_FAILED 예외로 변환한다")
    fun send_mailServerError_throwsEmailSendFailed() {
        emailSender = SmtpEmailSender(javaMailSender, "noreply@example.com")
        willThrow(MailSendException("SMTP connection failed"))
            .given(javaMailSender)
            .send(any(SimpleMailMessage::class.java))

        assertThatThrownBy { emailSender.send("test@example.com", "인증 코드", "코드: 123456") }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_SEND_FAILED)
    }
}
