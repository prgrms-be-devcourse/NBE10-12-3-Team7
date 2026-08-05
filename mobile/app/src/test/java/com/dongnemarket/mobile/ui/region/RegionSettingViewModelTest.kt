package com.dongnemarket.mobile.ui.region

import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.MemberLocation
import com.dongnemarket.mobile.domain.model.Region
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.domain.repository.MemberRepository
import com.dongnemarket.mobile.domain.repository.RegionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 동네 설정 화면 상태 보유자([RegionSettingViewModel])의 명세.
 *
 * ## 이 화면이 조용히 틀리기 쉬운 자리
 *  1. **기존 설정을 덮어쓴다** — `PUT` 이 전체 교체라, 이미 있던 동네를 초기값으로 안 채우면
 *     하나를 추가하려던 사용자가 나머지 하나를 잃는다. **데이터 유실이고 되돌릴 수 없다.**
 *  2. **대표 동네가 뒤바뀐다** — 서버는 보낸 리스트의 0번을 대표로 삼는다.
 *     순서를 흘리면 사용자가 고른 대표와 다른 동네가 홈에 찍힌다.
 *  3. **고를 수 없는 단계를 고르게 둔다** — 시·도를 골라 저장하면 서버가 400 을 주는데
 *     이유를 알려 주지 않는다.
 *
 * 셋 다 예외도 로그도 없이 틀린다. 그래서 여기에 테스트를 몰아 둔다.
 */
class RegionSettingViewModelTest {

    // 타입을 TestWatcher 로 적는 이유: 구현 클래스가 이 파일 전용 private 이라
    // 공개 프로퍼티가 private 타입을 노출하면 컴파일되지 않는다.
    @get:Rule
    val mainDispatcherRule: TestWatcher = MainDispatcherRule()

    private val regionRepository = mockk<RegionRepository>()
    private val memberRepository = mockk<MemberRepository>()

    // ─────────────────── 1. 기존 설정 이어받기 (데이터 유실 방지) ───────────────────

    @Test
    fun `화면에 들어오면 이미 설정된 내 동네가 선택값으로 채워진다`() = runTest {
        // Given: 이미 두 동네를 쓰고 있다
        내동네가(청운동_대표, 신교동_보조)
        지역목록이(parent = null, returns = listOf(서울, 부산))

        // When
        val viewModel = 화면을_연다()

        // Then: 이게 없으면 동네 하나를 **추가**하려고 들어온 사용자가
        // 하나만 고르고 저장했을 때 원래 있던 다른 하나를 잃는다(PUT 이 전체 교체다).
        assertEquals(
            listOf("1111010100", "1111010200"),
            viewModel.uiState.value.selected.map { it.code },
        )
    }

    @Test
    fun `기존 동네는 sortOrder 순서 그대로 들어온다 — 0번이 대표다`() = runTest {
        // Given: 응답이 sortOrder 역순으로 와도(서버 정렬을 신뢰하지 않는다)
        내동네가(신교동_보조, 청운동_대표)
        지역목록이(parent = null, returns = listOf(서울))

        // When
        val viewModel = 화면을_연다()

        // Then: 0번(sortOrder = 0)이 대표다. 순서가 뒤집히면 저장 시 대표가 바뀐다.
        assertEquals("1111010100", viewModel.uiState.value.selected.first().code)
    }

    @Test
    fun `동네를 설정한 적 없으면 빈 선택으로 시작한다`() = runTest {
        // Given: 미설정은 에러가 아니라 빈 리스트다(정상 성공)
        내동네가()
        지역목록이(parent = null, returns = listOf(서울))

        // When
        val viewModel = 화면을_연다()

        // Then
        val state = viewModel.uiState.value
        assertTrue(state.selected.isEmpty())
        assertFalse(state.isLoadingMine)
        assertFalse("아무것도 안 골랐으면 저장할 수 없다", state.canSave)
    }

    // ─────────────────── 2. 드릴다운 ───────────────────

    @Test
    fun `화면에 들어오면 최상위 시도 목록부터 받는다`() = runTest {
        // Given
        내동네가()
        지역목록이(parent = null, returns = listOf(서울, 부산))

        // When
        val viewModel = 화면을_연다()

        // Then
        val state = viewModel.uiState.value
        assertEquals(listOf("서울특별시", "부산광역시"), state.options.map { it.displayName })
        assertEquals(0, state.depth)
        assertNull("최상위에서는 찍을 경로가 없다", state.pathLabel)
    }

    @Test
    fun `시도를 누르면 그 아래 시군구를 받고 경로가 쌓인다`() = runTest {
        // Given
        내동네가()
        지역목록이(parent = null, returns = listOf(서울))
        지역목록이(parent = "11", returns = listOf(종로구))
        val viewModel = 화면을_연다()

        // When
        viewModel.onRegionClick(서울)

        // Then
        val state = viewModel.uiState.value
        assertEquals(listOf("종로구"), state.options.map { it.displayName })
        assertEquals(1, state.depth)
        assertEquals("서울특별시", state.pathLabel)
    }

    @Test
    fun `시군구까지 내려가면 경로가 화살표로 이어진다`() = runTest {
        // Given
        val viewModel = 종로구까지_내려간_화면()

        // Then: 지금 어디를 보고 있는지 알려 주지 않으면 5천 건 속에서 길을 잃는다
        assertEquals("서울특별시 › 종로구", viewModel.uiState.value.pathLabel)
        assertEquals(2, viewModel.uiState.value.depth)
    }

    @Test
    fun `읍면동을 누르면 파고들지 않고 선택된다`() = runTest {
        // Given
        val viewModel = 종로구까지_내려간_화면()

        // When
        viewModel.onRegionClick(청운동)

        // Then: 동은 마지막 단계다. 여기서 또 파고들면 빈 목록이 뜬다.
        val state = viewModel.uiState.value
        assertEquals(listOf("1111010100"), state.selected.map { it.code })
        assertEquals("경로가 더 깊어지면 안 된다", 2, state.depth)
    }

    @Test
    fun `뒤로 가면 한 단계 위 목록으로 돌아간다`() = runTest {
        // Given
        val viewModel = 종로구까지_내려간_화면()

        // When
        viewModel.onGoUp()

        // Then
        val state = viewModel.uiState.value
        assertEquals(1, state.depth)
        assertEquals("서울특별시", state.pathLabel)
        assertEquals(listOf("종로구"), state.options.map { it.displayName })
    }

    @Test
    fun `최상위에서는 더 위로 갈 수 없다`() = runTest {
        // Given
        내동네가()
        지역목록이(parent = null, returns = listOf(서울))
        val viewModel = 화면을_연다()

        // When
        viewModel.onGoUp()

        // Then: 화면은 canGoUp 이 false 면 뒤로가기를 "화면 닫기" 로 쓴다
        assertFalse(viewModel.uiState.value.canGoUp)
        assertEquals(0, viewModel.uiState.value.depth)
    }

    @Test
    fun `목록 조회에 실패하면 에러를 띄우고 재시도할 수 있다`() = runTest {
        // Given
        내동네가()
        coEvery { regionRepository.getRegions(parentCode = null) } returns
            Result.failure(AppError.Network())
        val viewModel = 화면을_연다()
        assertEquals("네트워크 연결을 확인해 주세요.", viewModel.uiState.value.optionsError)

        // When: 사용자가 다시 시도를 누른다
        지역목록이(parent = null, returns = listOf(서울))
        viewModel.onRetryOptions()

        // Then
        val state = viewModel.uiState.value
        assertNull(state.optionsError)
        assertEquals(listOf("서울특별시"), state.options.map { it.displayName })
    }

    // ─────────────────── 3. 선택 규칙 ───────────────────

    @Test
    fun `동네는 두 개까지만 고를 수 있다`() = runTest {
        // Given: 서버가 최대 2개만 저장한다
        val viewModel = 종로구까지_내려간_화면()
        viewModel.onRegionClick(청운동)
        viewModel.onRegionClick(신교동)

        // When: 세 번째를 누른다
        viewModel.onRegionClick(궁정동)

        // Then: 조용히 무시하지 않고 이유를 알린다
        val state = viewModel.uiState.value
        assertEquals(2, state.selected.size)
        assertEquals("동네는 최대 2개까지 설정할 수 있어요.", state.message)
    }

    @Test
    fun `이미 고른 동네를 다시 누르면 해제된다`() = runTest {
        // Given
        val viewModel = 종로구까지_내려간_화면()
        viewModel.onRegionClick(청운동)

        // When
        viewModel.onRegionClick(청운동)

        // Then: 목록에서 바로 취소할 수 있어야 상한에 걸렸을 때 위쪽 칩까지 올라가지 않아도 된다
        assertTrue(viewModel.uiState.value.selected.isEmpty())
    }

    @Test
    fun `같은 동네가 두 번 담기지 않는다`() = runTest {
        // Given: 서버는 중복이면 400 을 준다
        val viewModel = 종로구까지_내려간_화면()

        // When
        viewModel.onRegionClick(청운동)
        viewModel.onRegionClick(신교동)
        viewModel.onRegionClick(청운동) // 해제
        viewModel.onRegionClick(청운동) // 다시 선택

        // Then
        assertEquals(
            listOf("1111010200", "1111010100"),
            viewModel.uiState.value.selected.map { it.code },
        )
    }

    @Test
    fun `칩에서 빼면 선택에서 사라진다`() = runTest {
        // Given
        val viewModel = 종로구까지_내려간_화면()
        viewModel.onRegionClick(청운동)
        viewModel.onRegionClick(신교동)

        // When
        viewModel.onRemoveSelected("1111010100")

        // Then
        assertEquals(listOf("1111010200"), viewModel.uiState.value.selected.map { it.code })
    }

    // ─────────────────── 4. 대표 동네 = 순서 ───────────────────

    @Test
    fun `대표로 지정하면 맨 앞으로 옮겨진다`() = runTest {
        // Given: 청운동이 대표(0번)인 상태
        val viewModel = 종로구까지_내려간_화면()
        viewModel.onRegionClick(청운동)
        viewModel.onRegionClick(신교동)

        // When: 신교동을 대표로
        viewModel.onMakePrimary("1111010200")

        // Then: 서버에 "대표만 바꾸기" API 가 없고 **리스트 0번**을 대표로 삼는다
        // → 대표 변경은 곧 순서 변경이다. 플래그로 두면 순서와 어긋난다.
        assertEquals(
            listOf("1111010200", "1111010100"),
            viewModel.uiState.value.selected.map { it.code },
        )
    }

    @Test
    fun `이미 대표인 동네를 다시 눌러도 순서가 흐트러지지 않는다`() = runTest {
        // Given
        val viewModel = 종로구까지_내려간_화면()
        viewModel.onRegionClick(청운동)
        viewModel.onRegionClick(신교동)

        // When
        viewModel.onMakePrimary("1111010100")

        // Then
        assertEquals(
            listOf("1111010100", "1111010200"),
            viewModel.uiState.value.selected.map { it.code },
        )
    }

    // ─────────────────── 5. 저장 ───────────────────

    @Test
    fun `저장하면 고른 순서 그대로 코드가 전송된다`() = runTest {
        // Given
        val viewModel = 종로구까지_내려간_화면()
        viewModel.onRegionClick(청운동)
        viewModel.onRegionClick(신교동)
        viewModel.onMakePrimary("1111010200")

        val 보낸코드 = slot<List<String>>()
        coEvery { memberRepository.updateMyLocations(capture(보낸코드)) } returns
            Result.success(listOf(신교동_대표로))

        // When
        viewModel.onSave()

        // Then: **순서가 곧 대표 지정**이다. 정렬하거나 Set 으로 바꾸면 대표가 뒤바뀐다.
        assertEquals(listOf("1111010200", "1111010100"), 보낸코드.captured)
    }

    @Test
    fun `저장에 성공하면 서버가 준 최종 목록을 화면에 알린다`() = runTest {
        // Given
        val viewModel = 종로구까지_내려간_화면()
        viewModel.onRegionClick(청운동)
        coEvery { memberRepository.updateMyLocations(any()) } returns Result.success(listOf(청운동_대표))

        // When
        viewModel.onSave()

        // Then: 화면이 이 값을 보고 이전 화면으로 돌아간다
        val state = viewModel.uiState.value
        assertNotNull(state.savedRegions)
        assertEquals(listOf("1111010100"), state.savedRegions!!.map { it.code })
        assertFalse(state.isSaving)
    }

    @Test
    fun `아무것도 안 골랐으면 저장 요청이 나가지 않는다`() = runTest {
        // Given
        내동네가()
        지역목록이(parent = null, returns = listOf(서울))
        val viewModel = 화면을_연다()

        // When
        viewModel.onSave()

        // Then: 서버도 빈 목록이면 400 이지만, 그 전에 "동네 없음으로 저장" 은 의도로 보기 어렵다
        coVerify(exactly = 0) { memberRepository.updateMyLocations(any()) }
    }

    @Test
    fun `저장에 실패해도 고른 동네는 그대로 남는다`() = runTest {
        // Given
        val viewModel = 종로구까지_내려간_화면()
        viewModel.onRegionClick(청운동)
        viewModel.onRegionClick(신교동)
        coEvery { memberRepository.updateMyLocations(any()) } returns
            Result.failure(AppError.Api(status = 400, code = "INVALID_INPUT_VALUE", message = "잘못된 요청입니다."))

        // When
        viewModel.onSave()

        // Then: 여기서 초기화하면 사용자가 5천 건짜리 드릴다운을 처음부터 다시 해야 한다
        val state = viewModel.uiState.value
        assertEquals(2, state.selected.size)
        assertEquals("잘못된 요청입니다.", state.message)
        assertFalse("잠긴 채로 남으면 다시 시도할 수 없다", state.isSaving)
        assertNull(state.savedRegions)
    }

    @Test
    fun `저장하는 동안에는 화면이 잠겨 선택을 바꿀 수 없다`() = runTest {
        // Given: 저장 요청을 중간에 멈춰 세운다
        val viewModel = 종로구까지_내려간_화면()
        viewModel.onRegionClick(청운동)
        val 게이트 = CompletableDeferred<Unit>()
        coEvery { memberRepository.updateMyLocations(any()) } coAnswers {
            게이트.await()
            Result.success(listOf(청운동_대표))
        }
        viewModel.onSave()

        // When: 저장 중에 다른 동네를 누르고, 고른 것을 빼려 한다
        viewModel.onRegionClick(신교동)
        viewModel.onRemoveSelected("1111010100")

        // Then: 이미 보낸 요청과 화면이 어긋나면 무엇이 저장됐는지 아무도 모르게 된다
        assertEquals(listOf("1111010100"), viewModel.uiState.value.selected.map { it.code })
        게이트.complete(Unit)
    }

    @Test
    fun `저장 중에 다시 눌러도 중복 요청되지 않는다`() = runTest {
        // Given
        val viewModel = 종로구까지_내려간_화면()
        viewModel.onRegionClick(청운동)
        val 게이트 = CompletableDeferred<Unit>()
        coEvery { memberRepository.updateMyLocations(any()) } coAnswers {
            게이트.await()
            Result.success(listOf(청운동_대표))
        }
        viewModel.onSave()

        // When
        viewModel.onSave()
        viewModel.onSave()

        // Then
        게이트.complete(Unit)
        coVerify(exactly = 1) { memberRepository.updateMyLocations(any()) }
    }

    @Test
    fun `메시지를 한 번 보여 준 뒤에는 지운다`() = runTest {
        // Given
        val viewModel = 종로구까지_내려간_화면()
        viewModel.onRegionClick(청운동)
        coEvery { memberRepository.updateMyLocations(any()) } returns Result.failure(AppError.Network())
        viewModel.onSave()

        // When
        viewModel.onMessageShown()

        // Then: 안 지우면 화면 회전 때마다 같은 스낵바가 다시 뜬다
        assertNull(viewModel.uiState.value.message)
    }

    // ─────────────────── 픽스처 ───────────────────

    private fun 화면을_연다() = RegionSettingViewModel(regionRepository, memberRepository)

    /** 서울 › 종로구까지 내려가 읍·면·동 목록을 보고 있는 상태. 선택 관련 테스트의 배경이다. */
    private fun 종로구까지_내려간_화면(): RegionSettingViewModel {
        내동네가()
        지역목록이(parent = null, returns = listOf(서울))
        지역목록이(parent = "11", returns = listOf(종로구))
        지역목록이(parent = "1111", returns = listOf(청운동, 신교동, 궁정동))
        return 화면을_연다().apply {
            onRegionClick(서울)
            onRegionClick(종로구)
        }
    }

    private fun 내동네가(vararg locations: MemberLocation) {
        coEvery { memberRepository.getMyLocations() } returns Result.success(locations.toList())
    }

    private fun 지역목록이(parent: String?, returns: List<Region>) {
        coEvery { regionRepository.getRegions(parentCode = parent) } returns Result.success(returns)
    }

    private val 서울 = 지역("11", "서울특별시", level = 1, parent = null)
    private val 부산 = 지역("26", "부산광역시", level = 1, parent = null)
    private val 종로구 = 지역("1111", "종로구", level = 2, parent = "11")
    private val 청운동 = 지역("1111010100", "청운동", level = 3, parent = "1111")
    private val 신교동 = 지역("1111010200", "신교동", level = 3, parent = "1111")
    private val 궁정동 = 지역("1111010300", "궁정동", level = 3, parent = "1111")

    private val 청운동_대표 = 내동네(청운동, sortOrder = 0, active = true)
    private val 신교동_보조 = 내동네(신교동, sortOrder = 1, active = false)
    private val 신교동_대표로 = 내동네(신교동, sortOrder = 0, active = true)

    private fun 지역(code: String, name: String, level: Int, parent: String?) = Region(
        regionId = code.hashCode().toLong(),
        code = code,
        level = level,
        parentCode = parent,
        fullName = "서울특별시 종로구 $name",
        displayName = name,
    )

    private fun 내동네(region: Region, sortOrder: Int, active: Boolean) = MemberLocation(
        region = RegionRef(region.code, region.displayName, region.fullName),
        sortOrder = sortOrder,
        active = active,
    )
}

/**
 * `viewModelScope` 가 쓰는 `Dispatchers.Main` 을 테스트용으로 갈아 끼운다.
 *
 * 안 하면 ViewModel 생성 순간 "Module with the Main dispatcher had failed to initialize" 로 죽는다.
 * `UnconfinedTestDispatcher` 를 쓰므로 `viewModelScope.launch { }` 가 즉시 실행되어
 * 테스트에서 `advanceUntilIdle()` 없이 결과를 바로 읽을 수 있다.
 *
 * 이 파일 안에서만 쓰는 private 선언이다(다른 테스트 파일과 이름이 부딪히지 않는다).
 */
private class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
