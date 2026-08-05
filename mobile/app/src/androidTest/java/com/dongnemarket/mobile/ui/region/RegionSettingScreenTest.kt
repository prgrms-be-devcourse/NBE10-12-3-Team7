package com.dongnemarket.mobile.ui.region

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dongnemarket.mobile.domain.model.Region
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.ui.theme.MarketOnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 동네 설정 화면 Compose UI 테스트.
 *
 * ViewModel·Hilt·네트워크 없이 **stateless 인 [RegionSettingContent] 에 상태를 직접 먹여** 띄운다.
 *
 * ## 단위 테스트가 못 보는 것을 맡는다
 * [RegionSettingViewModelTest] 는 **상태가 옳게 바뀌는지**만 본다.
 * 여기서는 그 상태가 화면에 도달하는지, 그리고 **눌렀을 때 무엇이 올라오는지**를 본다:
 *  - 시·군·구와 읍·면·동이 **눈으로 구분되는가**(꺾쇠 vs 체크) — 없으면 누르기 전까지 알 수 없다
 *  - 대표 동네가 **어느 칩인지 글자로 드러나는가**
 *  - 저장 중에 목록이 실제로 잠기는가
 *
 * ⚠️ 테스트 이름에 **공백을 쓰지 않는다** — 백틱 이름이 DEX 메소드 SimpleName 이 되는데
 * DEX 039 가 공백·쉼표를 금지해서 기기 설치가 실패한다.
 */
class RegionSettingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // 화면이 붙인 testTag (RegionSettingScreen.kt 의 TAG_* 상수와 같은 문자열)
    private val listTag = "region_list"
    private val itemTag = "region_item"
    private val chipTag = "region_selected_chip"
    private val saveTag = "region_save"
    private val upTag = "region_up"

    // ──────────────────────── 1. 목록 표시 ────────────────────────

    @Test
    fun `목록을_받는_동안에는_로딩만_보인다`() {
        // Given
        composeTestRule.showRegion(state = 상태(isLoadingOptions = true, options = emptyList()))

        // Then
        composeTestRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(itemTag).assertCountEquals(0)
    }

    @Test
    fun `받은_지역_수만큼_항목이_그려진다`() {
        // Given
        composeTestRule.showRegion(state = 상태(options = listOf(서울, 부산, 대구)))

        // Then
        composeTestRule.onAllNodesWithTag(itemTag).assertCountEquals(3)
        composeTestRule.onNodeWithText("서울특별시").assertIsDisplayed()
    }

    @Test
    fun `시군구까지는_파고들_수_있다는_표시가_붙는다`() {
        // Given: 시·도 목록(고를 수 없는 단계)
        composeTestRule.showRegion(state = 상태(options = listOf(서울, 부산)))

        // Then: 이 표시가 없으면 누르기 전까지 "고르는 것" 인지 "들어가는 것" 인지 알 수 없다
        composeTestRule.onAllNodesWithContentDescription("하위 지역 보기").assertCountEquals(2)
    }

    @Test
    fun `읍면동에는_파고들기_표시가_붙지_않는다`() {
        // Given: 읍·면·동 목록(마지막 단계)
        composeTestRule.showRegion(state = 종로구_단계())

        // Then
        composeTestRule.onAllNodesWithContentDescription("하위 지역 보기").assertCountEquals(0)
    }

    @Test
    fun `이미_고른_동네에는_체크가_붙는다`() {
        // Given: 청운동을 이미 골랐다
        composeTestRule.showRegion(
            state = 종로구_단계().copy(selected = listOf(청운동_ref)),
        )

        // Then: 색만으로 구분하면 색 대비를 못 보는 사용자가 알 수 없다
        composeTestRule.onAllNodesWithContentDescription("선택됨").assertCountEquals(1)
    }

    @Test
    fun `목록_조회에_실패하면_에러와_재시도가_보인다`() {
        // Given
        var 재시도횟수 = 0
        composeTestRule.showRegion(
            state = 상태(options = emptyList(), optionsError = "네트워크 연결을 확인해 주세요."),
            onRetryOptions = { 재시도횟수++ },
        )

        // When
        composeTestRule.onNodeWithText("네트워크 연결을 확인해 주세요.").assertIsDisplayed()
        composeTestRule.onNodeWithText("다시 시도").performClick()

        // Then
        assertEquals(1, 재시도횟수)
    }

    // ──────────────────────── 2. 드릴다운 ────────────────────────

    @Test
    fun `항목을_누르면_그_지역이_그대로_전달된다`() {
        // Given
        var 누른지역: Region? = null
        composeTestRule.showRegion(
            state = 상태(options = listOf(서울, 부산, 대구)),
            onRegionClick = { 누른지역 = it },
        )

        // When: 두 번째 항목(부산)을 누른다
        composeTestRule.onAllNodesWithTag(itemTag)[1].performClick()

        // Then: "파고들지 선택할지" 판단은 ViewModel 이 한다 — 화면은 무엇을 눌렀는지만 알린다
        assertEquals("26", 누른지역?.code)
    }

    @Test
    fun `현재_경로가_화면에_찍힌다`() {
        // Given
        composeTestRule.showRegion(state = 종로구_단계())

        // Then: 5천 건짜리 계층에서 지금 어디인지 모르면 길을 잃는다
        composeTestRule.onNodeWithText("서울특별시 › 종로구").assertIsDisplayed()
    }

    @Test
    fun `최상위에서는_경로가_찍히지_않는다`() {
        // Given
        composeTestRule.showRegion(state = 상태(options = listOf(서울)))

        // Then: 찍을 것이 없는데 빈 줄을 남기면 화면이 어긋나 보인다
        composeTestRule.onNodeWithText("›", substring = true).assertDoesNotExist()
    }

    @Test
    fun `파고든_상태에서_뒤로가기는_상위_지역으로_간다`() {
        // Given
        var 위로갔나 = false
        var 화면닫았나 = false
        composeTestRule.showRegion(
            state = 종로구_단계(),
            onGoUp = { 위로갔나 = true },
            onBackClick = { 화면닫았나 = true },
        )

        // When
        composeTestRule.onNodeWithTag(upTag).performClick()

        // Then: 같은 버튼이 "한 단계 뒤로" 라는 한 가지 일을 한다 —
        // 파고든 상태에서 화면이 닫히면 사용자는 처음부터 다시 내려와야 한다
        assertTrue(위로갔나)
        assertTrue("화면을 닫으면 안 된다", !화면닫았나)
    }

    @Test
    fun `최상위에서_뒤로가기는_화면을_닫는다`() {
        // Given
        var 위로갔나 = false
        var 화면닫았나 = false
        composeTestRule.showRegion(
            state = 상태(options = listOf(서울)),
            onGoUp = { 위로갔나 = true },
            onBackClick = { 화면닫았나 = true },
        )

        // When
        composeTestRule.onNodeWithTag(upTag).performClick()

        // Then
        assertTrue(화면닫았나)
        assertTrue("더 올라갈 곳이 없다", !위로갔나)
    }

    // ──────────────────────── 3. 고른 동네 칩 ────────────────────────

    @Test
    fun `아무것도_고르지_않았으면_고르는_법을_알려_준다`() {
        // Given
        composeTestRule.showRegion(state = 상태(options = listOf(서울)))

        // Then: 시·도만 보이는 첫 화면에서 "왜 아무것도 선택이 안 되지" 를 막는다
        composeTestRule.onNodeWithText("읍·면·동까지 내려가야", substring = true).assertIsDisplayed()
    }

    @Test
    fun `고른_동네가_칩으로_보이고_개수가_찍힌다`() {
        // Given
        composeTestRule.showRegion(
            state = 종로구_단계().copy(selected = listOf(청운동_ref, 신교동_ref)),
        )

        // Then
        composeTestRule.onAllNodesWithTag(chipTag).assertCountEquals(2)
        composeTestRule.onNodeWithText("내 동네 (2/2)").assertIsDisplayed()
    }

    @Test
    fun `첫_칩이_대표로_표시된다`() {
        // Given: 서버가 리스트 0번을 대표(active)로 만든다
        composeTestRule.showRegion(
            state = 종로구_단계().copy(selected = listOf(청운동_ref, 신교동_ref)),
        )

        // Then: 어느 동네가 홈에 찍힐지를 화면이 말해 줘야 한다.
        //
        // 칩을 **순서로** 짚는 이유: "신교동" 은 칩과 아래 지역 목록 양쪽에 찍혀 있어
        // onNodeWithText 로는 어느 쪽을 검사했는지 알 수 없다.
        // 게다가 이 테스트의 주장은 "**첫** 칩이 대표" 이므로 순서를 직접 짚는 편이 주장에 맞다.
        composeTestRule.onAllNodesWithTag(chipTag)[0].assertTextContains("청운동 · 대표")
        composeTestRule.onAllNodesWithTag(chipTag)[1].assertTextContains("신교동")
    }

    @Test
    fun `칩을_누르면_대표_지정_요청이_간다`() {
        // Given
        var 대표로지정된코드: String? = null
        composeTestRule.showRegion(
            state = 종로구_단계().copy(selected = listOf(청운동_ref, 신교동_ref)),
            onMakePrimary = { 대표로지정된코드 = it },
        )

        // When: 두 번째 칩(신교동)을 누른다
        composeTestRule.onAllNodesWithTag(chipTag)[1].performClick()

        // Then
        assertEquals("1111010200", 대표로지정된코드)
    }

    @Test
    fun `칩의_엑스를_누르면_빼기_요청이_간다`() {
        // Given
        var 뺀코드: String? = null
        composeTestRule.showRegion(
            state = 종로구_단계().copy(selected = listOf(청운동_ref, 신교동_ref)),
            onRemoveSelected = { 뺀코드 = it },
        )

        // When
        composeTestRule.onNodeWithContentDescription("신교동 빼기").performClick()

        // Then: 칩 본체 탭(대표 지정)과 × 탭(빼기)이 같은 칩 안에서 갈라진다 —
        // 섞이면 빼려다 대표만 바뀐다
        assertEquals("1111010200", 뺀코드)
    }

    @Test
    fun `동네가_하나뿐이면_대표_안내를_띄우지_않는다`() {
        // Given: 바꿀 대상이 없으므로 안내가 소음이 된다
        composeTestRule.showRegion(state = 종로구_단계().copy(selected = listOf(청운동_ref)))

        // Then
        composeTestRule.onNodeWithText("칩을 누르면 대표 동네가 돼요.").assertDoesNotExist()
    }

    // ──────────────────────── 4. 저장 ────────────────────────

    @Test
    fun `아무것도_고르지_않았으면_저장_버튼이_잠긴다`() {
        // Given
        composeTestRule.showRegion(state = 상태(options = listOf(서울)))

        // Then
        composeTestRule.onNodeWithTag(saveTag).assertIsNotEnabled()
        composeTestRule.onNodeWithText("동네를 골라 주세요").assertIsDisplayed()
    }

    @Test
    fun `고른_동네가_있으면_개수가_버튼에_찍힌다`() {
        // Given
        composeTestRule.showRegion(
            state = 종로구_단계().copy(selected = listOf(청운동_ref, 신교동_ref)),
        )

        // Then
        composeTestRule.onNodeWithTag(saveTag).assertIsEnabled()
        composeTestRule.onNodeWithText("2개 동네로 저장").assertIsDisplayed()
    }

    @Test
    fun `저장_버튼을_누르면_저장_요청이_간다`() {
        // Given
        var 저장횟수 = 0
        composeTestRule.showRegion(
            state = 종로구_단계().copy(selected = listOf(청운동_ref)),
            onSave = { 저장횟수++ },
        )

        // When
        composeTestRule.onNodeWithTag(saveTag).performClick()

        // Then
        assertEquals(1, 저장횟수)
    }

    @Test
    fun `저장하는_동안에는_진행_표시가_뜨고_버튼이_잠긴다`() {
        // Given
        var 저장횟수 = 0
        composeTestRule.showRegion(
            state = 종로구_단계().copy(selected = listOf(청운동_ref), isSaving = true),
            onSave = { 저장횟수++ },
        )

        // When
        composeTestRule.onNodeWithTag(saveTag).performClick()

        // Then: 두 번 나가면 같은 요청이 두 번 처리된다
        composeTestRule.onNodeWithText("저장하는 중").assertIsDisplayed()
        composeTestRule.onNodeWithTag(saveTag).assertIsNotEnabled()
        assertEquals(0, 저장횟수)
    }

    @Test
    fun `저장하는_동안에는_지역_목록도_눌리지_않는다`() {
        // Given
        var 누른지역: Region? = null
        composeTestRule.showRegion(
            state = 종로구_단계().copy(selected = listOf(청운동_ref), isSaving = true),
            onRegionClick = { 누른지역 = it },
        )

        // When
        composeTestRule.onAllNodesWithTag(itemTag)[0].performClick()

        // Then: 이미 보낸 요청과 화면이 어긋나면 무엇이 저장됐는지 아무도 모르게 된다
        assertEquals(null, 누른지역)
    }

    // ──────────────────────── 픽스처 ────────────────────────

    private fun ComposeContentTestRule.showRegion(
        state: RegionSettingUiState,
        onRegionClick: (Region) -> Unit = {},
        onRemoveSelected: (String) -> Unit = {},
        onMakePrimary: (String) -> Unit = {},
        onGoUp: () -> Unit = {},
        onRetryOptions: () -> Unit = {},
        onSave: () -> Unit = {},
        onBackClick: () -> Unit = {},
    ) {
        setContent {
            MarketOnTheme {
                RegionSettingContent(
                    state = state,
                    onRegionClick = onRegionClick,
                    onRemoveSelected = onRemoveSelected,
                    onMakePrimary = onMakePrimary,
                    onGoUp = onGoUp,
                    onRetryOptions = onRetryOptions,
                    onSave = onSave,
                    onMessageShown = {},
                    onBackClick = onBackClick,
                )
            }
        }
    }

    private fun 상태(
        options: List<Region> = listOf(서울),
        path: List<Region> = emptyList(),
        selected: List<RegionRef> = emptyList(),
        isLoadingOptions: Boolean = false,
        optionsError: String? = null,
        isSaving: Boolean = false,
    ) = RegionSettingUiState(
        isLoadingMine = false,
        options = options,
        path = path,
        selected = selected,
        isLoadingOptions = isLoadingOptions,
        optionsError = optionsError,
        isSaving = isSaving,
    )

    /** 서울 › 종로구까지 내려가 읍·면·동을 보고 있는 상태. */
    private fun 종로구_단계() = 상태(
        options = listOf(청운동, 신교동),
        path = listOf(서울, 종로구),
    )

    private val 서울 = 지역("11", "서울특별시", level = 1, parent = null)
    private val 부산 = 지역("26", "부산광역시", level = 1, parent = null)
    private val 대구 = 지역("27", "대구광역시", level = 1, parent = null)
    private val 종로구 = 지역("1111", "종로구", level = 2, parent = "11")
    private val 청운동 = 지역("1111010100", "청운동", level = 3, parent = "1111")
    private val 신교동 = 지역("1111010200", "신교동", level = 3, parent = "1111")

    private val 청운동_ref = RegionRef("1111010100", "청운동", "서울특별시 종로구 청운동")
    private val 신교동_ref = RegionRef("1111010200", "신교동", "서울특별시 종로구 신교동")

    private fun 지역(code: String, name: String, level: Int, parent: String?) = Region(
        regionId = code.hashCode().toLong(),
        code = code,
        level = level,
        parentCode = parent,
        fullName = name,
        displayName = name,
    )
}
