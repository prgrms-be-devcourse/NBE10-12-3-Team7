package com.dongnemarket.auth.service

import com.dongnemarket.auth.dto.EmailVerificationConfirmRequest
import com.dongnemarket.auth.dto.EmailVerificationConfirmResponse
import com.dongnemarket.auth.dto.EmailVerificationRequest
import com.dongnemarket.auth.dto.EmailVerificationResponse
import com.dongnemarket.auth.entity.EmailVerification
import com.dongnemarket.auth.mail.EmailSender
import com.dongnemarket.auth.repository.EmailVerificationCodeRepository
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.repository.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDateTime

/**
 * 회원가입 전 이메일 인증 코드의 생성·저장·재요청 쿨다운을 담당한다.
 * 실제 발송은 [EmailSender] 에 위임한다(SMTP 등 발송 수단이 바뀌어도 이 클래스는 영향받지 않는다).
 *
 * 코드 자체(발급·비교·쿨다운·만료)는 TTL 데이터라 [EmailVerificationCodeRepository](Redis)가 담당하고,
 * "인증 완료" 여부만 [EmailVerificationRepository](DB)에 남긴다 — 회원가입은 코드 TTL 과 무관하게
 * 이 완료 상태를 확인하기 때문이다(`AuthService.signup` 참고).
 *
 * 전환 규칙 — **호출 순서가 계약이다.** 요청은 중복 이메일 검사 → 쿨다운 검사 → 코드 저장 →
 * 이전 인증 상태 무효화 → 메일 발송 순이고, 확인은 이미 인증됨 확인 → 코드 조회 → 문자열 일치 →
 * **코드 삭제 후** 인증 상태 반영 순이다. 메일 제목·본문 문자열과 코드 형식(6자리 zero-pad)도 그대로 둔다.
 */
@Service
@Transactional
class EmailVerificationService(
    private val emailVerificationCodeRepository: EmailVerificationCodeRepository,
    private val emailVerificationRepository: EmailVerificationRepository,
    private val memberRepository: MemberRepository,
    private val emailSender: EmailSender,
) {
    private val secureRandom = SecureRandom()

    fun requestVerification(request: EmailVerificationRequest): EmailVerificationResponse {
        val email = request.email
        if (memberRepository.existsByEmail(email)) {
            throw BusinessException(ErrorCode.DUPLICATE_EMAIL)
        }

        val ttl = Duration.ofMinutes(CODE_TTL_MINUTES)
        emailVerificationCodeRepository.getRemainingTtl(email).ifPresent { remaining ->
            if (remaining > ttl.minusSeconds(COOLDOWN_SECONDS)) {
                throw BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUEST_TOO_SOON)
            }
        }

        val code = generateCode()
        emailVerificationCodeRepository.save(email, code, ttl)
        // 새 코드를 발급했으니 이전 인증 상태(있었다면)는 무효화 — 새 코드에 대해 다시 인증해야 한다.
        emailVerificationRepository.findByEmail(email).ifPresent { it.unverify() }

        emailSender.send(email!!, "[마켓온] 이메일 인증 코드 안내", buildVerificationEmailBody(code))

        return EmailVerificationResponse(email, LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES))
    }

    private fun buildVerificationEmailBody(code: String): String =
        "안녕하세요, 마켓온입니다.\n\n" +
            "요청하신 이메일 인증 코드를 안내드립니다.\n\n" +
            "인증 코드: " + code + "\n\n" +
            "이 코드는 발급 시점으로부터 " + CODE_TTL_MINUTES + "분간 유효합니다.\n" +
            "본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.\n\n" +
            "감사합니다.\n" +
            "마켓온 드림"

    /**
     * 인증 코드를 확인한다. 이미 인증 완료된 건에 같은 이메일로 재요청하면(중복 확인) 코드 검사 없이
     * 그대로 성공을 반환한다(멱등).
     */
    fun confirmVerification(request: EmailVerificationConfirmRequest): EmailVerificationConfirmResponse {
        val email = request.email
        if (emailVerificationRepository.existsByEmailAndVerifiedTrue(email)) {
            return EmailVerificationConfirmResponse(email, true)
        }

        val storedCode =
            emailVerificationCodeRepository
                .findCode(email)
                .orElseThrow { BusinessException(ErrorCode.EMAIL_VERIFICATION_NOT_FOUND) }
        if (storedCode != request.code) {
            throw BusinessException(ErrorCode.INVALID_VERIFICATION_CODE)
        }

        emailVerificationCodeRepository.delete(email)
        val now = LocalDateTime.now()
        emailVerificationRepository
            .findByEmail(email)
            .ifPresentOrElse(
                { verification -> verification.verify(now) },
                { emailVerificationRepository.save(EmailVerification.verified(email, now)) },
            )

        return EmailVerificationConfirmResponse(email, true)
    }

    private fun generateCode(): String = String.format("%06d", secureRandom.nextInt(1_000_000))

    companion object {
        private const val COOLDOWN_SECONDS = 60L
        private const val CODE_TTL_MINUTES = 5L
    }
}
