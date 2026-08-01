package com.dongnemarket.auth.service

import com.dongnemarket.auth.dto.PasswordResetConfirmRequest
import com.dongnemarket.auth.dto.PasswordResetRequest
import com.dongnemarket.auth.mail.EmailSender
import com.dongnemarket.auth.repository.PasswordResetTokenRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

/**
 * 비밀번호 찾기(재설정 이메일 링크) 요청/확인을 담당한다.
 * 계정 존재 여부를 외부에 노출하지 않기 위해 요청 API 는 가입 여부·탈퇴 여부와 무관하게 항상 같은 응답을 반환한다
 * (실제 토큰 발급·발송은 활성/정지 회원에게만 조용히 수행된다).
 *
 * 원문 토큰은 이메일 링크로만 전달되고 저장소에는 SHA-256 해시만 저장한다.
 * 1회용·TTL 데이터라 [PasswordResetTokenRepository](Redis)에 담긴다 — 별도 정리(cleanup) 작업 없이
 * TTL 만료로 자연 삭제된다.
 *
 * 전환 규칙 — **호출 순서와 필터 조건이 보안 계약이다.**
 * 요청은 `DELETED` 아님 → 로컬 로그인 가능 회원만 통과시키고, 쿨다운 중이면 **조용히 반환**한다
 * (계정 존재 여부를 응답으로 노출하지 않는다). 재요청 시 **이전 토큰을 먼저 무효화**한 뒤 새 토큰을 저장한다.
 * 확인은 토큰 해시 조회 → 회원 조회 → 로컬 로그인 가드 → 비밀번호 변경 → **tokenHash 삭제 → memberId 삭제 →
 * refresh token 삭제** 순이며, 이 순서를 그대로 유지한다.
 * 메일 제목·본문·링크 형식(`{base}/password-reset?token={raw}`)도 문자열 그대로다.
 */
@Service
@Transactional
class PasswordResetService(
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val refreshTokenService: RefreshTokenService,
    private val emailSender: EmailSender,
    @param:Value("\${auth.frontend-base-url}") private val frontendBaseUrl: String,
) {
    private val secureRandom = SecureRandom()

    fun requestReset(request: PasswordResetRequest) {
        memberRepository
            .findByEmail(request.email)
            .filter { member -> member.status != MemberStatus.DELETED }
            .filter { member -> member.isLocalLoginEnabled }
            .ifPresent { member -> issueAndSendTokenIfNotCoolingDown(member) }
    }

    private fun issueAndSendTokenIfNotCoolingDown(member: Member) {
        val memberId = member.id
        val ttl = Duration.ofMinutes(TOKEN_TTL_MINUTES)

        val existingHash = passwordResetTokenRepository.findTokenHashByMemberId(memberId)
        if (existingHash.isPresent) {
            val remaining =
                passwordResetTokenRepository.getRemainingTtlByMemberId(memberId).orElse(Duration.ZERO)
            if (remaining > ttl.minusSeconds(COOLDOWN_SECONDS)) {
                return
            }
            // 재요청(쿨다운 경과): 이전 토큰은 즉시 무효화하고 새 토큰으로 교체한다.
            passwordResetTokenRepository.deleteByTokenHash(existingHash.get())
        }

        val rawToken = generateRawToken()
        val tokenHash = hash(rawToken)
        passwordResetTokenRepository.save(memberId, tokenHash, ttl)

        emailSender.send(member.email, "[마켓온] 비밀번호 재설정 안내", buildResetEmailBody(rawToken))
    }

    private fun buildResetEmailBody(rawToken: String): String {
        val resetLink = frontendBaseUrl + RESET_PAGE_PATH + "?token=" + rawToken
        return "안녕하세요, 마켓온입니다.\n\n" +
            "비밀번호 재설정을 요청하셨습니다. 아래 링크를 클릭해 새 비밀번호를 설정해주세요.\n\n" +
            resetLink + "\n\n" +
            "이 링크는 발급 시점으로부터 " + TOKEN_TTL_MINUTES + "분간 유효하며, 1회만 사용할 수 있습니다.\n" +
            "본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.\n\n" +
            "감사합니다.\n" +
            "마켓온 드림"
    }

    fun confirmReset(request: PasswordResetConfirmRequest) {
        val tokenHash = hash(request.token)
        val memberId =
            passwordResetTokenRepository
                .findMemberIdByTokenHash(tokenHash)
                .orElseThrow { BusinessException(ErrorCode.INVALID_RESET_TOKEN) }

        val member =
            memberRepository
                .findById(memberId)
                .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        // 정상 흐름상 도달 불가(소셜 전용 회원에게는 애초에 토큰이 발급되지 않는다) — 방어적 가드.
        if (!member.isLocalLoginEnabled) {
            throw BusinessException(ErrorCode.INVALID_RESET_TOKEN)
        }

        member.changePassword(passwordEncoder.encode(request.newPassword))
        passwordResetTokenRepository.deleteByTokenHash(tokenHash)
        passwordResetTokenRepository.deleteByMemberId(memberId)
        refreshTokenService.deleteByMemberId(member.id)
    }

    /** 링크에 담을 원문 토큰(256비트 무작위값, URL-safe). 저장소에는 해시만 남기고 원문은 저장하지 않는다. */
    private fun generateRawToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(rawToken: String?): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashed = digest.digest(rawToken!!.toByteArray())
            return HexFormat.of().formatHex(hashed)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e)
        }
    }

    companion object {
        private const val COOLDOWN_SECONDS = 60L
        private const val TOKEN_TTL_MINUTES = 30L
        private const val RESET_PAGE_PATH = "/password-reset"
    }
}
