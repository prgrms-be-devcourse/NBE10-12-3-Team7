package com.dongnemarket.auth.mail

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/**
 * 전환 규칙 — 발송 계약을 그대로 유지한다.
 * 발신자 표기(`마켓온 <주소>`), `SimpleMailMessage` 사용(HTML 아님), 필드 설정 순서
 * (from → to → subject → text), `MailException` 을 `EMAIL_SEND_FAILED` 로 매핑하는 처리,
 * 실패 로그에 수신자만 남기고 본문·자격 증명은 남기지 않는 점까지 원본과 같다.
 * `@Async` 를 새로 붙이지 않는다 — 원본이 동기 발송이다.
 */
@Component
class SmtpEmailSender(
    private val javaMailSender: JavaMailSender,
    @param:Value("\${spring.mail.username}") private val fromAddress: String,
) : EmailSender {
    override fun send(
        to: String,
        subject: String,
        content: String,
    ) {
        val message = SimpleMailMessage()
        message.from = "$SENDER_DISPLAY_NAME <$fromAddress>"
        message.setTo(to)
        message.subject = subject
        message.text = content

        try {
            javaMailSender.send(message)
        } catch (e: MailException) {
            log.error("이메일 발송 실패: to={}", to, e)
            throw BusinessException(ErrorCode.EMAIL_SEND_FAILED)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SmtpEmailSender::class.java)
        private const val SENDER_DISPLAY_NAME = "마켓온"
    }
}
