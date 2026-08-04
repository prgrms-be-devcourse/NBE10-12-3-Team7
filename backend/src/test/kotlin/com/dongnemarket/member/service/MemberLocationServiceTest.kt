package com.dongnemarket.member.service

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.dto.MemberLocationUpdateRequest
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberLocation
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberLocationRepository
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class MemberLocationServiceTest {

    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var memberLocationRepository: MemberLocationRepository

    @Mock
    lateinit var regionRepository: RegionRepository

    @InjectMocks
    lateinit var memberLocationService: MemberLocationService

    @Test
    fun `동네 1개를 설정하면 해당 동네가 활성 동네로 저장된다`() {
        val member = Member.createUser("one@example.com", "encodedPassword", "oneUser")
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))
        given(regionRepository.findByCode("1168010100")).willReturn(Optional.of(yeoksam()))
        given(memberLocationRepository.saveAll(any<List<MemberLocation>>())).willAnswer { it.getArgument<List<MemberLocation>>(0) }

        val responses =
            memberLocationService.updateMyLocations(
                1L,
                MemberLocationUpdateRequest(listOf("1168010100")),
            )

        assertThat(responses).hasSize(1)
        assertThat(responses[0].regionCode).isEqualTo("1168010100")
        assertThat(responses[0].regionName).isEqualTo("역삼동")
        assertThat(responses[0].regionFullName).isEqualTo("서울특별시 강남구 역삼동")
        assertThat(responses[0].sortOrder).isZero()
        assertThat(responses[0].active).isTrue()
        then(memberLocationRepository).should().deleteAllByMemberId(1L)
    }

    @Test
    fun `동네 2개를 설정하면 첫 번째 동네만 활성 동네로 저장된다`() {
        val member = Member.createUser("two@example.com", "encodedPassword", "twoUser")
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))
        given(regionRepository.findByCode("1168010100")).willReturn(Optional.of(yeoksam()))
        given(regionRepository.findByCode("1144012400")).willReturn(Optional.of(yeonnam()))
        given(memberLocationRepository.saveAll(any<List<MemberLocation>>())).willAnswer { it.getArgument<List<MemberLocation>>(0) }

        val responses =
            memberLocationService.updateMyLocations(
                1L,
                MemberLocationUpdateRequest(listOf("1168010100", "1144012400")),
            )

        assertThat(responses.map { it.regionCode })
            .containsExactly("1168010100", "1144012400")
        assertThat(responses.map { it.regionFullName })
            .containsExactly("서울특별시 강남구 역삼동", "서울특별시 마포구 연남동")
        assertThat(responses.map { it.sortOrder })
            .containsExactly(0, 1)
        assertThat(responses.map { it.active })
            .containsExactly(true, false)
    }

    @Test
    fun `동네를 재설정하면 기존 동네를 전부 삭제하고 새 동네로 교체한다`() {
        val member = Member.createUser("replace@example.com", "encodedPassword", "replaceUser")
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))
        given(regionRepository.findByCode("1171010100")).willReturn(Optional.of(jamsil()))
        given(memberLocationRepository.saveAll(any<List<MemberLocation>>())).willAnswer { it.getArgument<List<MemberLocation>>(0) }

        memberLocationService.updateMyLocations(
            1L,
            MemberLocationUpdateRequest(listOf("1171010100")),
        )

        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<MemberLocation>>
        then(memberLocationRepository).should().deleteAllByMemberId(1L)
        then(memberLocationRepository).should().saveAll(captor.capture())
        assertThat(captor.value).hasSize(1)
        assertThat(captor.value[0].regionCode).isEqualTo("1171010100")
        assertThat(captor.value[0].regionFullName).isEqualTo("서울특별시 송파구 잠실동")
    }

    @Test
    fun `설정한 동네가 없으면 빈 목록을 반환한다`() {
        val member = Member.createUser("empty@example.com", "encodedPassword", "emptyUser")
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))
        given(memberLocationRepository.findAllByMemberIdOrderBySortOrderAsc(1L)).willReturn(listOf())

        val responses = memberLocationService.getMyLocations(1L)

        assertThat(responses).isEmpty()
    }

    @Test
    fun `같은 값으로 재설정해도 전체 교체 방식으로 정상 처리된다`() {
        val member = Member.createUser("same@example.com", "encodedPassword", "sameUser")
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))
        given(regionRepository.findByCode("1168010100")).willReturn(Optional.of(yeoksam()))
        given(memberLocationRepository.saveAll(any<List<MemberLocation>>())).willAnswer { it.getArgument<List<MemberLocation>>(0) }

        val responses =
            memberLocationService.updateMyLocations(
                1L,
                MemberLocationUpdateRequest(listOf("1168010100")),
            )

        assertThat(responses).hasSize(1)
        assertThat(responses[0].regionFullName).isEqualTo("서울특별시 강남구 역삼동")
        then(memberLocationRepository).should().deleteAllByMemberId(1L)
    }

    @Test
    fun `리스트 안에 중복 지역이 있으면 동네를 설정할 수 없다`() {
        val member = Member.createUser("duplicate@example.com", "encodedPassword", "duplicateUser")
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))

        assertThatThrownBy {
            memberLocationService.updateMyLocations(
                1L,
                MemberLocationUpdateRequest(listOf("1168010100", "1168010100")),
            )
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE)
    }

    @Test
    fun `지역 마스터에 없는 regionCode이면 동네를 설정할 수 없다`() {
        val member = Member.createUser("unknown@example.com", "encodedPassword", "unknownUser")
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))
        given(regionRepository.findByCode("9999999999")).willReturn(Optional.empty())

        assertThatThrownBy {
            memberLocationService.updateMyLocations(
                1L,
                MemberLocationUpdateRequest(listOf("9999999999")),
            )
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE)
    }

    @Test
    fun `level 3이 아닌 regionCode이면 동네를 설정할 수 없다`() {
        val member = Member.createUser("level2@example.com", "encodedPassword", "level2User")
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))
        given(regionRepository.findByCode("1168000000")).willReturn(Optional.of(gangnam()))

        assertThatThrownBy {
            memberLocationService.updateMyLocations(
                1L,
                MemberLocationUpdateRequest(listOf("1168000000")),
            )
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE)
    }

    @Test
    fun `존재하지 않는 회원이면 동네를 설정할 수 없다`() {
        given(memberRepository.findById(1L)).willReturn(Optional.empty())

        assertThatThrownBy {
            memberLocationService.updateMyLocations(
                1L,
                MemberLocationUpdateRequest(listOf("1168010100")),
            )
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND)
    }

    @Test
    fun `탈퇴 회원이면 동네를 조회할 수 없다`() {
        val member = Member.createUser("deleted@example.com", "encodedPassword", "deletedUser")
        member.softDelete()
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))

        assertThatThrownBy { memberLocationService.getMyLocations(1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DELETED_MEMBER)
    }

    @Test
    fun `정지 회원이면 동네를 조회할 수 없다`() {
        val member = Member.createUser("suspended@example.com", "encodedPassword", "suspendedUser")
        member.changeStatus(MemberStatus.SUSPENDED)
        given(memberRepository.findById(1L)).willReturn(Optional.of(member))

        assertThatThrownBy { memberLocationService.getMyLocations(1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUSPENDED_MEMBER)
    }

    private fun seoul(): Region = Region.root("1100000000", "서울특별시", "서울특별시")

    private fun gangnam(): Region = Region.child("1168000000", 2, seoul(), "서울특별시 강남구", "강남구")

    private fun mapo(): Region = Region.child("1144000000", 2, seoul(), "서울특별시 마포구", "마포구")

    private fun songpa(): Region = Region.child("1171000000", 2, seoul(), "서울특별시 송파구", "송파구")

    private fun yeoksam(): Region = Region.child("1168010100", 3, gangnam(), "서울특별시 강남구 역삼동", "역삼동")

    private fun yeonnam(): Region = Region.child("1144012400", 3, mapo(), "서울특별시 마포구 연남동", "연남동")

    private fun jamsil(): Region = Region.child("1171010100", 3, songpa(), "서울특별시 송파구 잠실동", "잠실동")
}
