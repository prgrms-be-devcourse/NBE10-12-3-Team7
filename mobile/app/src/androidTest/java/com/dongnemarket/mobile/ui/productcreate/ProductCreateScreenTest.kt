package com.dongnemarket.mobile.ui.productcreate

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import com.dongnemarket.mobile.domain.model.Category
import com.dongnemarket.mobile.domain.model.MemberLocation
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.ui.theme.MarketOnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 상품 등록 화면 Compose UI 테스트.
 *
 * ViewModel·Hilt·네트워크 없이 **stateless 인 [ProductCreateContent] 에 상태를 직접 먹여서** 띄운다.
 * 그래서 이 파일은 "이 상태가 오면 화면이 이렇게 보이고, 이렇게 누르면 이 람다가 이 값으로 불린다" 는
 * 계약서에 가깝다.
 *
 * ## 왜 등록 화면에 UI 테스트가 필요한가
 * 단위 테스트([ProductCreateViewModelTest])는 **상태가 옳게 바뀌는지**만 본다.
 * 그 상태가 화면에 도달하지 못하는 경로 — 예를 들어
 *  - 검증 문구는 만들어졌는데 화면에 안 그린다,
 *  - `isFormLocked` 는 true 인데 버튼이 계속 눌린다,
 *  - 동네 미설정인데 빈 폼을 보여 준다
 * 는 여기서만 잡힌다. 그리고 이런 것들은 실기 검수에서야 발견되는 종류다.
 *
 * ⚠️ 테스트 이름에 **공백을 쓰지 않는다.** 백틱 이름은 DEX 메소드 SimpleName 이 되는데
 * DEX 039 는 공백·쉼표를 금지해서, JVM 테스트처럼 띄어 쓰면 기기에서 설치가 실패한다.
 */
class ProductCreateScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // 화면이 붙인 testTag (ProductCreateScreen.kt · ImagePickerRow.kt 의 TAG_* 상수와 같은 문자열)
    private val titleTag = "create_title"
    private val priceTag = "create_price"
    private val descriptionTag = "create_description"
    private val submitTag = "create_submit"
    private val addImageTag = "create_add_image"
    private val imageItemTag = "create_image_item"
    private val imageRowTag = "create_image_row"

    // ──────────────────────────── 1. 폼 로딩 ────────────────────────────

    @Test
    fun `폼을_불러오는_동안에는_로딩_인디케이터만_보인다`() {
        // Given
        composeTestRule.showCreate(state = ProductCreateUiState(isLoadingForm = true))

        // Then: 카테고리·동네가 없으면 입력할 수 없으므로 폼을 미리 그리지 않는다
        composeTestRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(titleTag).assertDoesNotExist()
    }

    @Test
    fun `폼_조회에_실패하면_에러_메시지와_재시도가_보인다`() {
        // Given
        var 재시도횟수 = 0
        composeTestRule.showCreate(
            state = ProductCreateUiState(
                isLoadingForm = false,
                formLoadError = "네트워크 연결을 확인해 주세요.",
            ),
            onRetryLoad = { 재시도횟수++ },
        )

        // Then
        composeTestRule.onNodeWithText("네트워크 연결을 확인해 주세요.").assertIsDisplayed()

        // When: 사용자가 재시도를 누른다
        composeTestRule.onNodeWithText("다시 시도").performClick()

        // Then: 등록은 쓰기라 자동 재시도를 걸지 않는다 — 사용자가 누른 것만 다시 나간다
        assertEquals(1, 재시도횟수)
    }

    @Test
    fun `동네를_설정하지_않았으면_폼_대신_안내가_보인다`() {
        // Given: 미설정은 에러가 아니라 빈 리스트다(성공 응답)
        composeTestRule.showCreate(state = 폼상태(locations = emptyList(), regionCode = null))

        // Then: regionCode 의 출처가 없어 등록이 원천적으로 불가능하다.
        // 빈 폼을 보여 주고 제출에서 막으면 사용자는 왜 안 되는지 알 수 없다.
        composeTestRule.onNodeWithText("동네를 먼저 설정해 주세요").assertIsDisplayed()
        composeTestRule.onNodeWithTag(titleTag).assertDoesNotExist()
    }

    @Test
    fun `동네가_없으면_등록_버튼도_눌리지_않는다`() {
        // Given
        composeTestRule.showCreate(state = 폼상태(locations = emptyList(), regionCode = null))

        // Then
        composeTestRule.onNodeWithTag(submitTag).assertIsNotEnabled()
    }

    @Test
    fun `동네_미설정_안내에서_설정_화면으로_갈_수_있다`() {
        // Given
        var 설정으로이동 = false
        composeTestRule.showCreate(
            state = 폼상태(locations = emptyList(), regionCode = null),
            onSetRegionClick = { 설정으로이동 = true },
        )

        // When
        composeTestRule.onNodeWithTag("create_set_region").performClick()

        // Then: 안내만 있고 갈 곳이 없으면 사용자는 앱을 껐다 켤 뿐이다.
        // 등록을 막았으면 막힌 이유를 푸는 길도 같이 줘야 한다.
        assertTrue(설정으로이동)
    }

    // ──────────────────────────── 2. 사진 ────────────────────────────

    @Test
    fun `사진을_고르지_않았으면_추가_버튼만_보인다`() {
        // Given
        composeTestRule.showCreate(state = 폼상태(images = emptyList()))

        // Then
        composeTestRule.onNodeWithTag(addImageTag).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(imageItemTag).assertCountEquals(0)
    }

    @Test
    fun `고른_사진_수만큼_썸네일_칸이_그려진다`() {
        // Given
        composeTestRule.showCreate(state = 폼상태(images = listOf("A", "B", "C")))

        // Then
        composeTestRule.onAllNodesWithTag(imageItemTag).assertCountEquals(3)
    }

    @Test
    fun `사진이_다섯_장이면_추가_버튼이_사라진다`() {
        // Given: 서버 상한이 5장이다
        composeTestRule.showCreate(state = 폼상태(images = listOf("A", "B", "C", "D", "E")))

        // Then: 눌러도 더 못 넣는 버튼을 남겨 두면 "왜 안 되지" 가 된다 → 아예 감춘다
        composeTestRule.onNodeWithTag(addImageTag).assertDoesNotExist()
    }

    @Test
    fun `다섯_번째_사진도_밀어서_대표로_지정할_수_있다`() {
        // Given: 96dp 타일 5개는 폰 화면 폭(1080px)을 넘어 마지막 장이 처음엔 화면 밖이다.
        // LazyRow 는 **보이는 것만 구성**하므로, 스크롤하지 않으면 5번째는 노드로 존재하지도 않는다.
        var 지정된위치: Int? = null
        composeTestRule.showCreate(
            state = 폼상태(images = listOf("A", "B", "C", "D", "E")),
            onThumbnailSelect = { 지정된위치 = it },
        )

        // When: 마지막 항목까지 민 뒤 그것을 누른다
        composeTestRule.onNodeWithTag(imageRowTag).performScrollToIndex(4)
        composeTestRule.onAllNodesWithTag(imageItemTag).onLast().performClick()

        // Then: 화면 밖에 있던 사진도 정확한 위치로 보고돼야 한다
        assertEquals(4, 지정된위치)
    }

    @Test
    fun `대표_사진에만_대표_배지가_붙는다`() {
        // Given: 3장 중 1번이 대표
        composeTestRule.showCreate(state = 폼상태(images = listOf("A", "B", "C"), thumbnailIndex = 1))

        // Then: 색 테두리만으로 구분하면 색 대비를 못 보는 사용자가 알 수 없다 → 글자로도 알린다
        composeTestRule.onAllNodesWithText("대표").assertCountEquals(1)
    }

    @Test
    fun `사진을_탭하면_그_위치로_대표_지정_요청이_간다`() {
        // Given
        var 지정된위치: Int? = null
        composeTestRule.showCreate(
            state = 폼상태(images = listOf("A", "B", "C"), thumbnailIndex = 0),
            onThumbnailSelect = { 지정된위치 = it },
        )

        // When: 세 번째 사진을 누른다
        composeTestRule.onAllNodesWithTag(imageItemTag)[2].performClick()

        // Then
        assertEquals(2, 지정된위치)
    }

    @Test
    fun `삭제_아이콘을_누르면_그_위치로_삭제_요청이_간다`() {
        // Given
        var 삭제된위치: Int? = null
        composeTestRule.showCreate(
            state = 폼상태(images = listOf("A", "B")),
            onRemoveImage = { 삭제된위치 = it },
        )

        // When: 두 번째 사진의 × 를 누른다
        composeTestRule.onAllNodesWithContentDescription("사진 삭제")[1].performClick()

        // Then: 사진 본체 탭(대표 지정)과 × 탭(삭제)이 같은 칸 안에서 갈라진다 —
        // 둘이 섞이면 지우려다 대표만 바뀌는 일이 생긴다
        assertEquals(1, 삭제된위치)
    }

    @Test
    fun `사진이_두_장_이상이면_대표_지정_방법을_알려_준다`() {
        // Given
        composeTestRule.showCreate(state = 폼상태(images = listOf("A", "B")))

        // Then: 탭으로 대표를 바꿀 수 있다는 걸 아무도 알려 주지 않으면 발견되지 않는 기능이다
        composeTestRule.onNodeWithText("사진을 탭하면 대표 사진으로 지정돼요.").assertIsDisplayed()
    }

    @Test
    fun `사진이_한_장이면_대표_안내를_띄우지_않는다`() {
        // Given: 고를 것이 없으므로 안내가 소음이 된다
        composeTestRule.showCreate(state = 폼상태(images = listOf("A")))

        // Then
        composeTestRule.onNodeWithText("사진을 탭하면 대표 사진으로 지정돼요.").assertDoesNotExist()
    }

    // ──────────────────────────── 3. 입력 ────────────────────────────

    @Test
    fun `제목을_입력하면_입력한_값이_그대로_올라온다`() {
        // Given: 빈 칸에서 시작한다.
        // performTextInput 은 **기존 값에 이어 붙이므로**, 값이 들어 있는 칸에 치면
        // 올라오는 것은 방금 친 글자가 아니라 합쳐진 문자열이다.
        var 입력값 = ""
        composeTestRule.showCreate(state = 폼상태(title = ""), onTitleChange = { 입력값 = it })

        // When
        composeTestRule.onNodeWithTag(titleTag).performTextInput("닌텐도")

        // Then
        assertEquals("닌텐도", 입력값)
    }

    @Test
    fun `가격을_입력하면_입력한_값이_그대로_올라온다`() {
        // Given: 숫자만 남기는 필터는 ViewModel 의 책임이다 — 화면은 친 대로 올린다
        var 입력값 = ""
        composeTestRule.showCreate(state = 폼상태(price = ""), onPriceChange = { 입력값 = it })

        // When
        composeTestRule.onNodeWithTag(priceTag).performTextInput("240000")

        // Then
        assertEquals("240000", 입력값)
    }

    @Test
    fun `카테고리_칩을_누르면_그_카테고리_id_가_올라온다`() {
        // Given
        var 선택된id: Long? = null
        composeTestRule.showCreate(state = 폼상태(), onCategorySelect = { 선택된id = it })

        // When
        composeTestRule.onNodeWithText("가구_인테리어").performClick()

        // Then
        assertEquals(3L, 선택된id)
    }

    @Test
    fun `내_동네가_둘이면_칩이_둘_다_보인다`() {
        // Given
        composeTestRule.showCreate(state = 폼상태())

        // Then: 가로 스크롤로 두면 오른쪽 칩이 가려질 수 있는데,
        // 여기서 동네는 **필수 입력**이라 선택지가 숨으면 안 된다
        composeTestRule.onNodeWithText("역삼동").assertIsDisplayed()
        composeTestRule.onNodeWithText("삼성동").assertIsDisplayed()
    }

    @Test
    fun `동네_칩을_누르면_지역_코드가_올라온다`() {
        // Given
        var 선택된코드: String? = null
        composeTestRule.showCreate(state = 폼상태(), onRegionSelect = { 선택된코드 = it })

        // When
        composeTestRule.onNodeWithText("삼성동").performClick()

        // Then: 화면에 보이는 것은 이름이지만 서버로 가는 것은 코드다
        assertEquals("1168010600", 선택된코드)
    }

    @Test
    fun `내_동네만_등록할_수_있다는_사실을_화면이_설명한다`() {
        // Given
        composeTestRule.showCreate(state = 폼상태())

        // Then: 설명이 없으면 "다른 동네는 왜 없지?" 가 된다
        composeTestRule.onNodeWithText("내 동네에서만 상품을 등록할 수 있어요.").assertIsDisplayed()
    }

    // ──────────────────────────── 4. 검증 표시 ────────────────────────────

    @Test
    fun `검증에_걸린_칸마다_이유가_화면에_뜬다`() {
        // Given: ViewModel 이 만든 필드 오류가 화면까지 도달하는지 본다.
        // 서버는 이 위반들을 전부 INVALID_INPUT_VALUE 하나로 뭉쳐 주므로
        // 어느 칸이 문제인지는 앱만 알고, 앱이 그리지 않으면 아무도 모른다.
        composeTestRule.showCreate(
            state = 폼상태(
                images = emptyList(),
                fieldErrors = FieldErrors(
                    images = "사진을 1장 이상 등록해 주세요.",
                    title = "제목을 입력해 주세요.",
                    price = "가격을 입력해 주세요.",
                    category = "카테고리를 선택해 주세요.",
                ),
            ),
        )

        // Then
        composeTestRule.onNodeWithText("사진을 1장 이상 등록해 주세요.").assertIsDisplayed()
        composeTestRule.onNodeWithText("제목을 입력해 주세요.").assertIsDisplayed()
        composeTestRule.onNodeWithText("가격을 입력해 주세요.").assertIsDisplayed()
        composeTestRule.onNodeWithText("카테고리를 선택해 주세요.").assertIsDisplayed()
    }

    @Test
    fun `오류가_없으면_빨간_문구도_없다`() {
        // Given
        composeTestRule.showCreate(state = 폼상태())

        // Then
        composeTestRule.onNodeWithText("제목을 입력해 주세요.").assertDoesNotExist()
    }

    // ──────────────────────────── 5. 제출 단계 ────────────────────────────

    @Test
    fun `입력_중에는_등록_버튼이_눌린다`() {
        // Given
        var 제출횟수 = 0
        composeTestRule.showCreate(state = 폼상태(), onSubmit = { 제출횟수++ })

        // When
        composeTestRule.onNodeWithTag(submitTag).performClick()

        // Then: 입력이 덜 됐어도 버튼은 살아 있다 —
        // 비활성으로 두면 "무엇이 부족한지" 를 알려 줄 기회 자체가 사라진다
        assertEquals(1, 제출횟수)
    }

    @Test
    fun `업로드_중에는_진행률이_버튼에_찍힌다`() {
        // Given
        composeTestRule.showCreate(state = 폼상태(phase = CreatePhase.Uploading(done = 2, total = 5)))

        // Then: 사진 여러 장은 수 초가 걸린다. 스피너만 돌면 멈춘 건지 알 수 없다.
        composeTestRule.onNodeWithText("사진 올리는 중 2/5").assertIsDisplayed()
    }

    @Test
    fun `상품을_만드는_중에는_등록하는_중으로_바뀐다`() {
        // Given: 사진을 다 올린 뒤 단계다
        composeTestRule.showCreate(state = 폼상태(phase = CreatePhase.Creating))

        // Then
        composeTestRule.onNodeWithText("등록하는 중").assertIsDisplayed()
    }

    @Test
    fun `진행_중에는_등록_버튼이_잠긴다`() {
        // Given
        var 제출횟수 = 0
        composeTestRule.showCreate(
            state = 폼상태(phase = CreatePhase.Uploading(done = 1, total = 3)),
            onSubmit = { 제출횟수++ },
        )

        // When
        composeTestRule.onNodeWithTag(submitTag).performClick()

        // Then: 등록은 쓰기다. 두 번 나가면 같은 상품이 두 개 생긴다.
        composeTestRule.onNodeWithTag(submitTag).assertIsNotEnabled()
        assertEquals(0, 제출횟수)
    }

    @Test
    fun `진행_중에는_입력칸도_잠긴다`() {
        // Given
        composeTestRule.showCreate(state = 폼상태(phase = CreatePhase.Creating))

        // Then: 이미 보낸 요청과 화면이 어긋나면 무엇이 등록됐는지 아무도 모르게 된다
        composeTestRule.onNodeWithTag(titleTag).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(priceTag).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(descriptionTag).assertIsNotEnabled()
    }

    @Test
    fun `진행_중에는_뒤로가기도_잠긴다`() {
        // Given
        var 뒤로가기횟수 = 0
        composeTestRule.showCreate(
            state = 폼상태(phase = CreatePhase.Uploading(done = 1, total = 2)),
            onBackClick = { 뒤로가기횟수++ },
        )

        // When
        composeTestRule.onNodeWithContentDescription("뒤로").performClick()

        // Then: 업로드 도중에 화면을 떠나면 코루틴이 끊겨 서버에 고아 파일만 남는다
        assertEquals(0, 뒤로가기횟수)
    }

    @Test
    fun `실패해서_편집으로_돌아오면_버튼이_다시_살아난다`() {
        // Given: ViewModel 이 실패 후 Editing 으로 되돌린 상태
        var 제출횟수 = 0
        composeTestRule.showCreate(state = 폼상태(phase = CreatePhase.Editing), onSubmit = { 제출횟수++ })

        // When
        composeTestRule.onNodeWithTag(submitTag).performClick()

        // Then: 여기가 잠긴 채로 남으면 사용자가 고칠 수도 다시 보낼 수도 없다
        composeTestRule.onNodeWithTag(submitTag).assertIsEnabled()
        assertEquals(1, 제출횟수)
    }

    @Test
    fun `입력_중에는_뒤로가기가_동작한다`() {
        // Given
        var 뒤로가기횟수 = 0
        composeTestRule.showCreate(state = 폼상태(), onBackClick = { 뒤로가기횟수++ })

        // When
        composeTestRule.onNodeWithContentDescription("뒤로").performClick()

        // Then
        assertEquals(1, 뒤로가기횟수)
    }

    // ──────────────────────────── 픽스처 ────────────────────────────

    /**
     * 상태 하나를 화면에 먹여 띄운다. 인자로 준 콜백만 바꿔서
     * "무엇을 눌렀을 때 무엇이 불렸는지" 가 테스트 본문에 그대로 보이게 한다.
     */
    private fun ComposeContentTestRule.showCreate(
        state: ProductCreateUiState,
        onBackClick: () -> Unit = {},
        onSetRegionClick: () -> Unit = {},
        onRemoveImage: (Int) -> Unit = {},
        onThumbnailSelect: (Int) -> Unit = {},
        onTitleChange: (String) -> Unit = {},
        onPriceChange: (String) -> Unit = {},
        onCategorySelect: (Long) -> Unit = {},
        onRegionSelect: (String) -> Unit = {},
        onSubmit: () -> Unit = {},
        onRetryLoad: () -> Unit = {},
    ) {
        setContent {
            MarketOnTheme {
                ProductCreateContent(
                    state = state,
                    onBackClick = onBackClick,
                    onSetRegionClick = onSetRegionClick,
                    // 사진 선택기는 시스템 화면이라 Compose 테스트가 띄울 수 없다 —
                    // 여기서는 "고른 뒤" 상태를 직접 먹이는 방식으로 검증한다.
                    onImagesPicked = {},
                    onRemoveImage = onRemoveImage,
                    onThumbnailSelect = onThumbnailSelect,
                    onTitleChange = onTitleChange,
                    onDescriptionChange = {},
                    onPriceChange = onPriceChange,
                    onCategorySelect = onCategorySelect,
                    onRegionSelect = onRegionSelect,
                    onSubmit = onSubmit,
                    onSubmitErrorShown = {},
                    onRetryLoad = onRetryLoad,
                )
            }
        }
    }

    /** 정상적으로 열린 폼. 검증하려는 값만 인자로 바꾼다. */
    private fun 폼상태(
        images: List<String> = listOf("A"),
        thumbnailIndex: Int = 0,
        locations: List<MemberLocation> = 내동네_둘,
        regionCode: String? = "1168010300",
        phase: CreatePhase = CreatePhase.Editing,
        fieldErrors: FieldErrors = FieldErrors(),
        title: String = "닌텐도 스위치",
        price: String = "240000",
    ) = ProductCreateUiState(
        isLoadingForm = false,
        categories = 카테고리_4종,
        myLocations = locations,
        imageUris = images,
        thumbnailIndex = thumbnailIndex,
        title = title,
        priceInput = price,
        selectedCategoryId = 1L,
        selectedRegionCode = regionCode,
        phase = phase,
        fieldErrors = fieldErrors,
    )

    private val 카테고리_4종 = listOf(
        Category(id = 1L, name = "디지털기기"),
        Category(id = 2L, name = "생활가전"),
        Category(id = 3L, name = "가구_인테리어"),
        Category(id = 4L, name = "의류"),
    )

    private val 내동네_둘 = listOf(
        MemberLocation(
            region = RegionRef("1168010300", "역삼동", "서울특별시 강남구 역삼동"),
            sortOrder = 0,
            active = true,
        ),
        MemberLocation(
            region = RegionRef("1168010600", "삼성동", "서울특별시 강남구 삼성동"),
            sortOrder = 1,
            active = false,
        ),
    )
}
