package com.dongnemarket.auth.mail

/**
 * 이메일 발송 인터페이스. 이메일 인증 코드, 비밀번호 재설정 링크 등에서 공통으로 사용한다.
 * 발송 수단(SMTP 등)이 바뀌어도 이 인터페이스를 쓰는 코드는 변경할 필요가 없도록 분리한다.
 */
interface EmailSender {
    fun send(
        to: String,
        subject: String,
        content: String,
    )
}
