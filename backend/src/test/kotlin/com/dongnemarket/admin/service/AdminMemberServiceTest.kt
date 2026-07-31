package com.dongnemarket.admin.service

import com.dongnemarket.admin.dto.AdminMemberStatusUpdateRequest
import com.dongnemarket.admin.repository.AdminMemberRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional

/**
 * [단위] AdminMemberService.changeMemberStatus — 서비스 고유 로직만 검증.
 *  - 검증 대상: parseStatus(파싱·검증) + NOT_FOUND 예외.
 *  - 제외: deletedAt 전이(Member.changeStatus → MemberTest 커버), getMembers/getMember 위임(통합 커버).
 */
@ExtendWith(MockitoExtension::class)
class AdminMemberServiceTest {
    @Mock
    lateinit var adminMemberRepository: AdminMemberRepository

    @InjectMocks
    lateinit var adminMemberService: AdminMemberService

    private fun existingMember(): Member = Member.createUser("u@example.com", "encoded", "user")

    @Nested
    @DisplayName("성공 케이스")
    inner class Success {
        @Test
        fun `앞뒤 공백이 있어도 trim 후 파싱되어 상태가 변경된다`() {
            val member = existingMember()
            given(adminMemberRepository.findById(1L)).willReturn(Optional.of(member))

            val response = adminMemberService.changeMemberStatus(1L, AdminMemberStatusUpdateRequest("  SUSPENDED  "))

            assertThat(response.status).isEqualTo(MemberStatus.SUSPENDED)
            assertThat(member.status).isEqualTo(MemberStatus.SUSPENDED)
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    inner class Failure {
        /**
         * null·공백·정의되지 않은 값·대소문자 불일치는 모두 같은 분기(INVALID_MEMBER_STATUS)로 귀결.
         * 같은 결과라 메서드를 쪼개지 않고 파라미터로 묶는다.
         * 주의: changeMemberStatus가 findById를 먼저 호출하므로 회원 존재를 스텁해야
         *       NOT_FOUND가 먼저 터지지 않는다.
         *
         * 파라미터가 `String?` 인 것은 @NullSource 때문이다. non-null 로 두면 null 이 주입되는 순간
         * Kotlin 이 넣은 null 검사에 먼저 걸려 검증하려던 INVALID_MEMBER_STATUS 가 아니라
         * NullPointerException 으로 실패한다.
         */
        @ParameterizedTest(name = "[{index}] status=\"{0}\" → INVALID_MEMBER_STATUS")
        @NullSource
        @ValueSource(strings = ["", "   ", "FOO", "suspended", "Active"])
        @DisplayName("상태값이 null·공백·오타·대소문자 불일치면 INVALID_MEMBER_STATUS")
        fun invalidStatusValue_throwsInvalid(status: String?) {
            given(adminMemberRepository.findById(1L)).willReturn(Optional.of(existingMember()))

            val ex =
                assertThrows<BusinessException> {
                    adminMemberService.changeMemberStatus(1L, AdminMemberStatusUpdateRequest(status))
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_MEMBER_STATUS)
        }

        @Test
        fun `요청 객체 자체가 null이어도 INVALID_MEMBER_STATUS로 방어한다`() {
            given(adminMemberRepository.findById(1L)).willReturn(Optional.of(existingMember()))

            val ex = assertThrows<BusinessException> { adminMemberService.changeMemberStatus(1L, null) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_MEMBER_STATUS)
        }

        @Test
        fun `존재하지 않는 회원의 상태를 변경하면 MEMBER_NOT_FOUND 예외가 발생한다`() {
            given(adminMemberRepository.findById(999L)).willReturn(Optional.empty())

            val ex =
                assertThrows<BusinessException> {
                    adminMemberService.changeMemberStatus(999L, AdminMemberStatusUpdateRequest("SUSPENDED"))
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.MEMBER_NOT_FOUND)
        }
    }
}
