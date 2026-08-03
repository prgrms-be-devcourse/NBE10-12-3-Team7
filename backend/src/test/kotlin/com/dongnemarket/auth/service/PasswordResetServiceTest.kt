package com.dongnemarket.auth.service

import com.dongnemarket.auth.dto.PasswordResetConfirmRequest
import com.dongnemarket.auth.dto.PasswordResetRequest
import com.dongnemarket.auth.mail.EmailSender
import com.dongnemarket.auth.repository.InMemoryPasswordResetTokenRepository
import com.dongnemarket.auth.repository.PasswordResetTokenRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.HexFormat
import java.util.Optional

/**
 * 토큰 저장소는 실제 [InMemoryPasswordResetTokenRepository](2-key TTL 흉내)를 사용해
 * member↔tokenHash 양방향 조회가 실제로 맞물려 동작하는지까지 검증한다.
 */
@ExtendWith(MockitoExtension::class)
class PasswordResetServiceTest {
    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var refreshTokenService: RefreshTokenService

    @Mock
    lateinit var emailSender: EmailSender

    lateinit var passwordResetTokenRepository: PasswordResetTokenRepository
    val passwordEncoder: PasswordEncoder = BCryptPasswordEncoder()
    lateinit var passwordResetService: PasswordResetService

    @BeforeEach
    fun setUp() {
        passwordResetTokenRepository = InMemoryPasswordResetTokenRepository()
        passwordResetService =
            PasswordResetService(
                passwordResetTokenRepository,
                memberRepository,
                passwordEncoder,
                refreshTokenService,
                emailSender,
                FRONTEND_BASE_URL,
            )
    }

    /** 발송된 메일 본문의 링크(?token=...)에서 원문 토큰을 꺼낸다. */
    private fun captureSentRawToken(): String {
        val bodyCaptor: ArgumentCaptor<String> = ArgumentCaptor.forClass(String::class.java)
        verify(emailSender).send(anyString(), anyString(), cap(bodyCaptor))
        val body = bodyCaptor.value
        val index = body.indexOf("?token=")
        val afterToken = body.substring(index + "?token=".length)
        return afterToken.split(Regex("\\s"), 2)[0]
    }

    // ===== requestReset =====

    @Test
    @DisplayName("가입된 이메일이면 재설정 토큰 해시를 저장하고, 이메일에는 원문 토큰이 담긴 링크를 발송한다")
    fun requestReset_registeredEmail_issuesAndSendsToken() {
        val member = Member.createUser("test@example.com", "encoded", "tester")
        ReflectionTestUtils.setField(member, "id", 1L)
        given(memberRepository.findByEmail("test@example.com")).willReturn(Optional.of(member))

        passwordResetService.requestReset(PasswordResetRequest("test@example.com"))

        val rawToken = captureSentRawToken()
        assertThat(passwordResetTokenRepository.findTokenHashByMemberId(1L)).contains(sha256(rawToken))
        assertThat(passwordResetTokenRepository.findMemberIdByTokenHash(sha256(rawToken))).contains(1L)
    }

    @Test
    @DisplayName("메일 본문의 재설정 링크는 프론트 고정 주소 + query parameter(?token=)로 구성된다")
    fun requestReset_emailBody_containsQueryParamBasedLink() {
        val member = Member.createUser("link@example.com", "encoded", "tester")
        ReflectionTestUtils.setField(member, "id", 10L)
        given(memberRepository.findByEmail("link@example.com")).willReturn(Optional.of(member))

        passwordResetService.requestReset(PasswordResetRequest("link@example.com"))

        val bodyCaptor: ArgumentCaptor<String> = ArgumentCaptor.forClass(String::class.java)
        verify(emailSender).send(anyString(), anyString(), cap(bodyCaptor))
        assertThat(bodyCaptor.value).contains("$FRONTEND_BASE_URL/password-reset?token=")
    }

    @Test
    @DisplayName("가입되지 않은 이메일이면 예외 없이 조용히 종료하고 아무것도 저장·발송하지 않는다")
    fun requestReset_unregisteredEmail_doesNothingSilently() {
        given(memberRepository.findByEmail("none@example.com")).willReturn(Optional.empty())

        passwordResetService.requestReset(PasswordResetRequest("none@example.com"))

        verify(emailSender, never()).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("탈퇴한 회원이면 예외 없이 조용히 종료하고 아무것도 저장·발송하지 않는다")
    fun requestReset_deletedMember_doesNothingSilently() {
        val member = Member.createUser("deleted@example.com", "encoded", "tester")
        member.softDelete()
        given(memberRepository.findByEmail("deleted@example.com")).willReturn(Optional.of(member))

        passwordResetService.requestReset(PasswordResetRequest("deleted@example.com"))

        verify(emailSender, never()).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("정지된 회원이어도 재설정 토큰을 저장하고 발송한다")
    fun requestReset_suspendedMember_issuesAndSendsToken() {
        val member = Member.createUser("suspended@example.com", "encoded", "tester")
        member.changeStatus(MemberStatus.SUSPENDED)
        ReflectionTestUtils.setField(member, "id", 2L)
        given(memberRepository.findByEmail("suspended@example.com")).willReturn(Optional.of(member))

        passwordResetService.requestReset(PasswordResetRequest("suspended@example.com"))

        assertThat(passwordResetTokenRepository.findTokenHashByMemberId(2L)).isPresent()
        verify(emailSender).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("60초 이내에 재요청하면 예외 없이 조용히 종료하고 재발송하지 않는다")
    fun requestReset_withinCooldown_doesNotResend() {
        val member = Member.createUser("cooldown@example.com", "encoded", "tester")
        ReflectionTestUtils.setField(member, "id", 3L)
        given(memberRepository.findByEmail("cooldown@example.com")).willReturn(Optional.of(member))
        // 10초 전 발급을 흉내: 30분 TTL 중 29분50초(1790초) 남음 = 1740초(29분) 초과이므로 쿨다운 중
        passwordResetTokenRepository.save(3L, "old-hash", Duration.ofSeconds(1790))

        passwordResetService.requestReset(PasswordResetRequest("cooldown@example.com"))

        assertThat(passwordResetTokenRepository.findTokenHashByMemberId(3L)).contains("old-hash")
        verify(emailSender, never()).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("쿨다운이 지난 뒤 재요청하면 기존 토큰 해시를 교체하고 재발송한다")
    fun requestReset_afterCooldown_replacesAndResends() {
        val member = Member.createUser("resend@example.com", "encoded", "tester")
        ReflectionTestUtils.setField(member, "id", 4L)
        given(memberRepository.findByEmail("resend@example.com")).willReturn(Optional.of(member))
        // 61초 전 발급을 흉내: 30분 TTL 중 1739초(28분59초) 남음 = 1740초 이하이므로 쿨다운 지남
        passwordResetTokenRepository.save(4L, "old-hash", Duration.ofSeconds(1739))

        passwordResetService.requestReset(PasswordResetRequest("resend@example.com"))

        assertThat(passwordResetTokenRepository.findTokenHashByMemberId(4L)).isNotEqualTo(Optional.of("old-hash"))
        assertThat(passwordResetTokenRepository.findMemberIdByTokenHash("old-hash")).isEmpty()
        verify(emailSender).send(anyString(), anyString(), anyString())
    }

    @Test
    @DisplayName("소셜 로그인 전용 회원이면 예외 없이 조용히 종료하고 아무것도 저장·발송하지 않는다(가입되지 않은 이메일과 동일한 응답)")
    fun requestReset_socialOnlyAccount_doesNotSendEmail() {
        val member = Member.createSocialUser("social@example.com", "dummy-encoded", "kakao_abcdefghij")
        given(memberRepository.findByEmail("social@example.com")).willReturn(Optional.of(member))

        passwordResetService.requestReset(PasswordResetRequest("social@example.com"))

        verify(emailSender, never()).send(anyString(), anyString(), anyString())
    }

    // ===== confirmReset =====

    @Test
    @DisplayName("[방어적 가드] 소셜 로그인 전용 회원의 memberId로 저장된 토큰이 있어도(정상 흐름상 불가능) INVALID_RESET_TOKEN 예외가 발생하고 비밀번호를 바꾸지 않는다")
    fun confirmReset_tokenBelongsToSocialOnlyAccount_throwsException() {
        val member = Member.createSocialUser("social-confirm@example.com", "dummy-encoded", "kakao_zzzzzzzzzz")
        ReflectionTestUtils.setField(member, "id", 8L)
        val rawToken = "social-only-raw-token"
        passwordResetTokenRepository.save(8L, sha256(rawToken), Duration.ofMinutes(29))
        given(memberRepository.findById(8L)).willReturn(Optional.of(member))
        val originalPassword = member.password

        assertThatThrownBy {
            passwordResetService.confirmReset(
                PasswordResetConfirmRequest(rawToken, "newPassword123!"),
            )
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESET_TOKEN)

        assertThat(member.password).isEqualTo(originalPassword)
        verify(refreshTokenService, never()).deleteByMemberId(anyLong())
    }

    @Test
    @DisplayName("유효한 토큰이면 비밀번호를 변경하고 토큰을 즉시 삭제하며 Refresh Token을 삭제한다")
    fun confirmReset_validToken_success() {
        val member = Member.createUser("confirm@example.com", "old-encoded", "tester")
        ReflectionTestUtils.setField(member, "id", 5L)
        val rawToken = "valid-raw-token"
        passwordResetTokenRepository.save(5L, sha256(rawToken), Duration.ofMinutes(29))
        given(memberRepository.findById(5L)).willReturn(Optional.of(member))

        passwordResetService.confirmReset(PasswordResetConfirmRequest(rawToken, "newPassword123!"))

        assertThat(passwordEncoder.matches("newPassword123!", member.password)).isTrue()
        assertThat(passwordResetTokenRepository.findMemberIdByTokenHash(sha256(rawToken))).isEmpty()
        assertThat(passwordResetTokenRepository.findTokenHashByMemberId(5L)).isEmpty()
        verify(refreshTokenService).deleteByMemberId(5L)
    }

    @Test
    @DisplayName("존재하지 않는 토큰이면 INVALID_RESET_TOKEN 예외가 발생한다")
    fun confirmReset_tokenNotFound_throwsException() {
        assertThatThrownBy {
            passwordResetService.confirmReset(
                PasswordResetConfirmRequest("unknown-token", "newPassword123!"),
            )
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESET_TOKEN)

        verify(refreshTokenService, never()).deleteByMemberId(anyLong())
    }

    @Test
    @DisplayName("이미 사용되어 폐기된(삭제된) 토큰으로 재시도하면 INVALID_RESET_TOKEN 예외가 발생한다")
    fun confirmReset_alreadyUsedAndDeletedToken_throwsException() {
        val rawToken = "used-raw-token"
        val member = Member.createUser("used@example.com", "old-encoded", "tester")
        ReflectionTestUtils.setField(member, "id", 6L)
        passwordResetTokenRepository.save(6L, sha256(rawToken), Duration.ofMinutes(29))
        given(memberRepository.findById(6L)).willReturn(Optional.of(member))
        passwordResetService.confirmReset(PasswordResetConfirmRequest(rawToken, "firstNewPassword123!"))

        assertThatThrownBy {
            passwordResetService.confirmReset(
                PasswordResetConfirmRequest(rawToken, "secondNewPassword123!"),
            )
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESET_TOKEN)
    }

    @Test
    @DisplayName("만료된(TTL이 지나 저장소에서 사라진) 토큰이면 INVALID_RESET_TOKEN 예외가 발생한다")
    fun confirmReset_expiredToken_throwsException() {
        val rawToken = "expired-raw-token"
        // TTL을 이미 지난 값(음수 Duration)으로 저장해 "만료로 자연 삭제된" 상태를 흉내낸다.
        passwordResetTokenRepository.save(7L, sha256(rawToken), Duration.ofSeconds(-1))

        assertThatThrownBy {
            passwordResetService.confirmReset(
                PasswordResetConfirmRequest(rawToken, "newPassword123!"),
            )
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESET_TOKEN)

        verify(refreshTokenService, never()).deleteByMemberId(anyLong())
    }

    companion object {
        private const val FRONTEND_BASE_URL = "http://localhost:3000"

        private fun sha256(rawToken: String): String {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                return HexFormat.of().formatHex(digest.digest(rawToken.toByteArray()))
            } catch (e: NoSuchAlgorithmException) {
                throw IllegalStateException(e)
            }
        }

        /**
         * Mockito 매처는 null 을 반환하는데, Kotlin **non-null 파라미터** 자리에 넣으면 호출부
         * intrinsic null 검사("must not be null")가 매처 등록 전에 터진다. 매처를 등록한 뒤
         * 타입만 맞춘 값을 돌려주는 표준 우회다(mockito-kotlin 과 같은 방식).
         */
        @Suppress("UNCHECKED_CAST")
        fun <T> cap(captor: ArgumentCaptor<T>): T {
            captor.capture()
            return null as T
        }
    }
}
