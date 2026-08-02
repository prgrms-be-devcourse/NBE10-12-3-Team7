package com.dongnemarket.mobile.data.repository

import com.dongnemarket.mobile.data.remote.MemberApiService
import com.dongnemarket.mobile.data.remote.dto.ApiEnvelope
import com.dongnemarket.mobile.data.remote.dto.MemberLocationResponseDto
import com.dongnemarket.mobile.data.remote.dto.MemberLocationUpdateRequestDto
import com.dongnemarket.mobile.data.remote.dto.MemberResponseDto
import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.Member
import com.dongnemarket.mobile.domain.model.MemberLocation
import com.dongnemarket.mobile.domain.model.MemberRole
import com.dongnemarket.mobile.domain.model.MemberStatus
import com.dongnemarket.mobile.domain.model.RegionRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ## MemberRepositoryImpl 명세
 *
 * 이 Repository 가 실제로 "판단" 하는 곳은 세 군데뿐이다.
 *  1. `role`/`status` 문자열 → 도메인 enum **폴백 매핑**(MemberMapper) — 모르는 값에 앱이 죽으면 안 된다
 *  2. `getActiveRegionName()` 의 **대표 동네 선택 규칙**(active 우선, 없으면 sortOrder 최솟값)
 *  3. `updateMyLocations()` 의 **선(先)검증** — 서버가 모든 위반을 400 하나로 뭉쳐 주기 때문
 *
 * 나머지는 껍데기 벗기기와 1:1 매핑이라, 여기 있는 테스트도 위 세 가지에 집중한다.
 */
class MemberRepositoryImplTest {

    private val api: MemberApiService = mockk()
    private val repository = MemberRepositoryImpl(api)

    // ── 1. 내 정보 매핑: 모르는 값이 와도 앱이 죽지 않는다 ──────────────────────────

    @Test
    fun `내 정보 응답이 도메인 Member 로 매핑된다`() = runTest {
        // Given: 서버가 준 GET /api/members/me 응답
        coEvery { api.getMyProfile() } returns ApiEnvelope(
            status = 200,
            data = MemberResponseDto(
                memberId = 3,
                email = "buyer@marketon.com",
                nickname = "동네주민",
                role = "ROLE_USER",
                status = "ACTIVE",
                createdAt = "2026-07-26T13:45:30.123456",
            ),
        )

        // When
        val result = repository.getMyProfile()

        // Then: ROLE_ 접두는 떨어지고, 나머지는 그대로 옮겨진다
        assertEquals(
            Member(
                memberId = 3,
                email = "buyer@marketon.com",
                nickname = "동네주민",
                role = MemberRole.USER,
                status = MemberStatus.ACTIVE,
                createdAt = "2026-07-26T13:45:30.123456",
            ),
            result.getOrNull(),
        )
    }

    @Test
    fun `백엔드에 새 권한이 생겨 모르는 role 이 와도 UNKNOWN 으로 강등된다`() = runTest {
        // Given: 백엔드에 ROLE_MANAGER 가 추가된 상황
        coEvery { api.getMyProfile() } returns ApiEnvelope(
            status = 200,
            data = memberDto(role = "ROLE_MANAGER"),
        )

        // When
        val result = repository.getMyProfile()

        // Then: valueOf 였다면 여기서 예외가 터져 **세션 확인 자체가 죽는다**
        assertEquals(MemberRole.UNKNOWN, result.getOrNull()?.role)
    }

    @Test
    fun `role 키가 아예 없어도 예외 없이 UNKNOWN 이 된다`() = runTest {
        // Given: 서버가 role 을 빼고 응답
        coEvery { api.getMyProfile() } returns ApiEnvelope(
            status = 200,
            data = memberDto(role = null),
        )

        // When
        val result = repository.getMyProfile()

        // Then
        assertEquals(MemberRole.UNKNOWN, result.getOrNull()?.role)
    }

    @Test
    fun `role 이 소문자로 와도 ADMIN 으로 매핑된다`() = runTest {
        // Given: 표기가 흔들린 응답(대소문자·앞뒤 공백)
        coEvery { api.getMyProfile() } returns ApiEnvelope(
            status = 200,
            data = memberDto(role = " role_admin "),
        )

        // When
        val result = repository.getMyProfile()

        // Then: 매퍼가 trim + uppercase 로 흡수한다
        assertEquals(MemberRole.ADMIN, result.getOrNull()?.role)
    }

    @Test
    fun `모르는 status 문자열이 와도 UNKNOWN 으로 강등된다`() = runTest {
        // Given: 서버가 상태를 하나 추가한 상황
        coEvery { api.getMyProfile() } returns ApiEnvelope(
            status = 200,
            data = memberDto(status = "DORMANT"),
        )

        // When
        val result = repository.getMyProfile()

        // Then
        assertEquals(MemberStatus.UNKNOWN, result.getOrNull()?.status)
    }

    // ── 2. 내 동네 조회: 빈 배열은 실패가 아니라 "미설정" 이다 ──────────────────────

    @Test
    fun `내 동네가 빈 배열이면 실패가 아니라 빈 리스트 성공이다`() = runTest {
        // Given: 동네를 아직 설정하지 않은 회원
        coEvery { api.getMyLocations() } returns ApiEnvelope(status = 200, data = emptyList())

        // When
        val result = repository.getMyLocations()

        // Then: 여기서 실패로 만들면 신규 가입자가 홈에서 에러 화면을 본다
        assertEquals(emptyList<MemberLocation>(), result.getOrNull())
    }

    @Test
    fun `내 동네가 없으면 대표 동네 이름은 실패가 아니라 null 이다`() = runTest {
        // Given
        coEvery { api.getMyLocations() } returns ApiEnvelope(status = 200, data = emptyList())

        // When
        val result = repository.getActiveRegionName()

        // Then: Result 는 성공이고 값만 null — 호출부는 이걸 "동네 설정 화면으로" 신호로 읽는다
        assertNull(result.getOrThrow())
    }

    @Test
    fun `active 인 동네가 있으면 그 동네 이름을 대표로 준다`() = runTest {
        // Given: 서버가 sortOrder 0 번에 active 를 켜 준 정상 응답
        coEvery { api.getMyLocations() } returns ApiEnvelope(
            status = 200,
            data = listOf(
                MemberLocationResponseDto(regionCode = "11680", regionName = "강남구", regionFullName = "서울특별시 강남구", sortOrder = 0, active = true),
                MemberLocationResponseDto(regionCode = "11650", regionName = "서초구", regionFullName = "서울특별시 서초구", sortOrder = 1, active = false),
            ),
        )

        // When
        val result = repository.getActiveRegionName()

        // Then
        assertEquals("강남구", result.getOrThrow())
    }

    @Test
    fun `active 가 하나도 없으면 sortOrder 가 가장 작은 동네로 폴백한다`() = runTest {
        // Given: 서버 규칙이 흔들려 active 가 전부 false 로 온 응답
        coEvery { api.getMyLocations() } returns ApiEnvelope(
            status = 200,
            data = listOf(
                MemberLocationResponseDto(regionCode = "11650", regionName = "서초구", regionFullName = "서울특별시 서초구", sortOrder = 1, active = false),
                MemberLocationResponseDto(regionCode = "11680", regionName = "강남구", regionFullName = "서울특별시 강남구", sortOrder = 0, active = false),
            ),
        )

        // When
        val result = repository.getActiveRegionName()

        // Then: 폴백이 없으면 동네가 있는 사용자가 설정 화면으로 쫓겨난다
        assertEquals("강남구", result.getOrThrow())
    }

    // ── 3. 내 동네 저장: 서버에 보내기 전에 우리가 먼저 막는다 ─────────────────────

    @Test
    fun `동네를 0개로 저장하려 하면 서버를 부르지 않는다`() = runTest {
        // Given: 사용자가 아무것도 고르지 않고 저장을 눌렀다

        // When
        repository.updateMyLocations(regionCodes = emptyList())

        // Then: 왕복 없이 즉시 막는다
        coVerify(exactly = 0) { api.updateMyLocations(any()) }
    }

    @Test
    fun `동네를 0개로 저장하려 하면 사람이 읽을 수 있는 400 실패를 준다`() = runTest {
        // Given / When
        val result = repository.updateMyLocations(regionCodes = emptyList())

        // Then: 서버가 주는 INVALID_INPUT_VALUE 는 무엇이 틀렸는지 알려주지 않으므로 여기서 문장을 만든다
        assertEquals(
            "동네를 최소 1개 선택해 주세요.",
            (result.exceptionOrNull() as AppError).userMessage,
        )
    }

    @Test
    fun `동네를 3개 보내면 최대 2개라고 안내하며 실패한다`() = runTest {
        // Given / When
        val result = repository.updateMyLocations(
            regionCodes = listOf("11680", "11650", "11710"),
        )

        // Then
        assertEquals(
            "동네는 최대 2개까지 설정할 수 있어요.",
            (result.exceptionOrNull() as AppError).userMessage,
        )
    }

    @Test
    fun `같은 동네를 두 번 보내면 중복이라고 안내하며 실패한다`() = runTest {
        // Given / When
        val result = repository.updateMyLocations(
            regionCodes = listOf("11680", "11680"),
        )

        // Then
        assertEquals(
            "같은 동네를 두 번 선택할 수 없어요.",
            (result.exceptionOrNull() as AppError).userMessage,
        )
    }

    @Test
    fun `공백뿐인 동네 코드가 섞이면 실패한다`() = runTest {
        // Given / When
        val result = repository.updateMyLocations(regionCodes = listOf("11680", "  "))

        // Then
        assertEquals(
            "동네 코드가 비어 있어요.",
            (result.exceptionOrNull() as AppError).userMessage,
        )
    }

    @Test
    fun `선검증 실패는 400 상태의 AppError Api 로 온다`() = runTest {
        // Given / When
        val result = repository.updateMyLocations(regionCodes = emptyList())

        // Then: 네트워크 실패와 구분되도록 상태 코드를 붙여 둔다
        val error = result.exceptionOrNull()
        assertTrue(error is AppError.Api && error.status == 400)
    }

    @Test
    fun `유효한 동네 목록은 그대로 서버로 나간다`() = runTest {
        // Given
        val sentRequest = slot<MemberLocationUpdateRequestDto>()
        coEvery { api.updateMyLocations(capture(sentRequest)) } returns ApiEnvelope(
            status = 200,
            data = listOf(
                MemberLocationResponseDto(regionCode = "11680", regionName = "강남구", regionFullName = "서울특별시 강남구", sortOrder = 0, active = true),
            ),
        )

        // When
        repository.updateMyLocations(regionCodes = listOf("11680"))

        // Then: 0번 원소가 서버에서 대표 동네가 되므로 순서를 바꿔서도 안 된다
        assertEquals(listOf("11680"), sentRequest.captured.regionCodes)
    }

    @Test
    fun `동네 저장 응답은 갱신된 전체 목록으로 매핑돼 돌아온다`() = runTest {
        // Given: 서버가 0번을 대표(active=true)로 만들어 되돌려준다
        coEvery { api.updateMyLocations(any()) } returns ApiEnvelope(
            status = 200,
            data = listOf(
                MemberLocationResponseDto(regionCode = "11680", regionName = "강남구", regionFullName = "서울특별시 강남구", sortOrder = 0, active = true),
                MemberLocationResponseDto(regionCode = "11650", regionName = "서초구", regionFullName = "서울특별시 서초구", sortOrder = 1, active = false),
            ),
        )

        // When
        val result = repository.updateMyLocations(listOf("11680", "11650"))

        // Then
        assertEquals(
            listOf(
                MemberLocation(region = RegionRef(code = "11680", name = "강남구", fullName = "서울특별시 강남구"), sortOrder = 0, active = true),
                MemberLocation(region = RegionRef(code = "11650", name = "서초구", fullName = "서울특별시 서초구"), sortOrder = 1, active = false),
            ),
            result.getOrNull(),
        )
    }

    // ── 테스트 지원 ────────────────────────────────────────────────────────────────

    /** role/status 만 바꿔 가며 매핑을 확인하기 위한 기본 회원 응답. */
    private fun memberDto(
        role: String? = "ROLE_USER",
        status: String? = "ACTIVE",
    ) = MemberResponseDto(
        memberId = 3,
        email = "buyer@marketon.com",
        nickname = "동네주민",
        role = role,
        status = status,
        createdAt = "2026-07-26T13:45:30.123456",
    )
}
