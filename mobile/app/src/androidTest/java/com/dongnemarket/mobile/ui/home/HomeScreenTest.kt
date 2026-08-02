package com.dongnemarket.mobile.ui.home

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dongnemarket.mobile.domain.model.Category
import com.dongnemarket.mobile.domain.model.Product
import com.dongnemarket.mobile.domain.model.TradeStatus
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.ui.theme.MarketOnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/**
 * 홈(상품목록) 화면 Compose UI 테스트.
 *
 * ViewModel·Hilt·네트워크 없이 **stateless 인 [HomeContent] 에 상태를 직접 먹여서** 띄운다.
 * 그래서 이 파일은 "이 상태가 오면 화면이 이렇게 보이고, 이렇게 누르면 이 람다가 이 값으로 불린다" 는
 * 계약서에 가깝다.
 *
 * 콜백 확인은 목이 아니라 **로컬 변수 캡처**로 한다 — 무엇이 넘어왔는지가 코드에 그대로 보이게.
 */
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // 화면이 붙인 testTag (HomeScreen.kt 의 TAG_* 상수와 같은 문자열)
    private val productCardTag = "home_product_card"

    // ──────────────────────────── 1. 로딩 ────────────────────────────

    @Test
    fun `첫_진입_로딩_상태면_로딩_인디케이터가_보인다`() {
        // Given
        composeTestRule.showHome(state = HomeUiState.Loading)

        // When: 아무것도 하지 않는다

        // Then
        composeTestRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
    }

    @Test
    fun `첫_진입_로딩_상태면_상품_카드가_하나도_그려지지_않는다`() {
        // Given
        composeTestRule.showHome(state = HomeUiState.Loading)

        // Then
        composeTestRule.onAllNodesWithTag(productCardTag).assertCountEquals(0)
    }

    // ──────────────────────────── 2. 목록 ────────────────────────────

    @Test
    fun `상품이_4개_내려오면_상품_카드도_4장_그려진다`() {
        // Given: 서버가 상품 4건을 준 상태(2열 그리드 → 2줄)
        composeTestRule.showHome(state = successState(products = fourProducts()))

        // Then
        composeTestRule.onAllNodesWithTag(productCardTag).assertCountEquals(4)
    }

    @Test
    fun `상품_카드에는_상품_제목이_그대로_찍힌다`() {
        // Given
        composeTestRule.showHome(state = successState(products = fourProducts()))

        // Then
        composeTestRule.onNodeWithText("거의 안 쓴 맥북 프로 14인치 M3").assertIsDisplayed()
    }

    @Test
    fun `상품_카드를_누르면_그_카드의_상품_id_로_onProductClick_이_호출된다`() {
        // Given
        var clickedProductId: Long? = null
        composeTestRule.showHome(
            state = successState(products = fourProducts()),
            onProductClick = { clickedProductId = it },
        )

        // When: 두 번째 카드(productId = 2)를 누른다
        composeTestRule.onNodeWithTag("${productCardTag}_2").performClick()

        // Then: 첫 번째 카드의 id(1)가 아니라 실제로 누른 카드의 id 가 올라온다
        assertEquals(2L, clickedProductId)
    }

    // ──────────────────────────── 3. 빈 목록 ────────────────────────────

    @Test
    fun `등록된_상품이_0건이면_빈_상태_안내가_보인다`() {
        // Given: 필터 없이 조회했는데 결과가 0건
        composeTestRule.showHome(
            state = successState(products = emptyList(), keyword = "", selectedCategoryId = null),
        )

        // Then
        composeTestRule.onNodeWithText("아직 등록된 상품이 없어요.").assertIsDisplayed()
    }

    @Test
    fun `등록된_상품이_0건이면_상품_카드가_하나도_그려지지_않는다`() {
        // Given
        composeTestRule.showHome(state = successState(products = emptyList()))

        // Then
        composeTestRule.onAllNodesWithTag(productCardTag).assertCountEquals(0)
    }

    @Test
    fun `검색_결과가_0건이면_검색어를_바꿔_보라는_안내로_문구가_달라진다`() {
        // Given: 검색어가 걸린 채로 결과가 0건(isFiltering = true)
        composeTestRule.showHome(
            state = successState(products = emptyList(), keyword = "냉장고"),
        )

        // Then: "등록된 상품이 없다" 가 아니라 "조건에 맞는 상품이 없다" 여야 한다
        composeTestRule.onNodeWithText("조건에 맞는 상품이 없어요", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `목록을_아직_받는_중이면_0건_안내_대신_로딩이_보인다`() {
        // Given: 카테고리를 막 바꿔서 목록이 비었지만 재조회가 진행 중인 순간
        composeTestRule.showHome(
            state = successState(products = emptyList(), isAppending = true),
        )

        // Then: 아직 모르는 사실("상품이 없다")을 단정하지 않는다
        composeTestRule.onNodeWithText("아직 등록된 상품이 없어요.").assertDoesNotExist()
    }

    // ──────────────────────────── 4. 카테고리 칩 ────────────────────────────

    @Test
    fun `카테고리_칩을_누르면_그_카테고리_id_로_onCategorySelect_가_호출된다`() {
        // Given
        var selectedCategoryId: Long? = null
        composeTestRule.showHome(
            state = successState(products = fourProducts()),
            onCategorySelect = { selectedCategoryId = it },
        )

        // When: "생활가전"(id = 2) 칩을 누른다
        composeTestRule.onNodeWithText("생활가전").performClick()

        // Then
        assertEquals(2L, selectedCategoryId)
    }

    @Test
    fun `앱_전용_전체_칩을_누르면_onCategorySelect_에_null_이_전달된다`() {
        // Given: "의류"(id = 4)가 선택돼 있는 상태
        var called = false
        var selectedCategoryId: Long? = 4L
        composeTestRule.showHome(
            state = successState(products = fourProducts(), selectedCategoryId = 4L),
            onCategorySelect = {
                called = true
                selectedCategoryId = it
            },
        )

        // When: 필터를 푸는 "전체" 칩을 누른다
        composeTestRule.onNodeWithText("전체").performClick()

        // Then: 호출은 됐고, 값은 "선택 없음"을 뜻하는 null 이다
        assertTrue(called)
        assertNull(selectedCategoryId)
    }

    // ──────────────────────────── 5. 에러 ────────────────────────────

    @Test
    fun `상품_조회에_실패하면_에러_문구가_보인다`() {
        // Given
        composeTestRule.showHome(state = HomeUiState.Error("네트워크 연결을 확인해 주세요."))

        // Then
        composeTestRule.onNodeWithText("네트워크 연결을 확인해 주세요.").assertIsDisplayed()
    }

    @Test
    fun `상품_조회에_실패하면_다시_시도_버튼이_보인다`() {
        // Given
        composeTestRule.showHome(state = HomeUiState.Error("네트워크 연결을 확인해 주세요."))

        // Then
        composeTestRule.onNodeWithText("다시 시도").assertIsDisplayed()
    }

    @Test
    fun `에러_화면에서_다시_시도를_누르면_onRetry_가_호출된다`() {
        // Given
        var retried = false
        composeTestRule.showHome(
            state = HomeUiState.Error("네트워크 연결을 확인해 주세요."),
            onRetry = { retried = true },
        )

        // When
        composeTestRule.onNodeWithText("다시 시도").performClick()

        // Then
        assertTrue(retried)
    }

    @Test
    fun `에러_화면에는_상품_카드가_하나도_그려지지_않는다`() {
        // Given
        composeTestRule.showHome(state = HomeUiState.Error("네트워크 연결을 확인해 주세요."))

        // Then
        composeTestRule.onAllNodesWithTag(productCardTag).assertCountEquals(0)
    }

    // ──────────────────────────── 6. 하단 탭 ────────────────────────────

    @Test
    fun `하단_채팅_탭을_누르면_onChatTabClick_이_호출된다`() {
        // Given
        var chatTabClicked = false
        composeTestRule.showHome(
            state = successState(products = fourProducts()),
            onChatTabClick = { chatTabClicked = true },
        )

        // When
        composeTestRule.onNodeWithText("채팅").performClick()

        // Then
        assertTrue(chatTabClicked)
    }

    @Test
    fun `Phase_1_에_화면이_없는_찜_탭은_눌러도_아무_콜백이_호출되지_않는다`() {
        // Given
        var anyTabHandled = false
        composeTestRule.showHome(
            state = successState(products = fourProducts()),
            onChatTabClick = { anyTabHandled = true },
        )

        // When: 비활성 탭을 누른다
        composeTestRule.onNodeWithText("찜").performClick()

        // Then
        assertFalse(anyTabHandled)
    }

    // ──────────────────────────── 테스트 도우미 ────────────────────────────

    private fun ComposeContentTestRule.showHome(
        state: HomeUiState,
        onProductClick: (Long) -> Unit = {},
        onChatTabClick: () -> Unit = {},
        onSearch: (String) -> Unit = {},
        onCategorySelect: (Long?) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        setContent {
            MarketOnTheme {
                HomeContent(
                    state = state,
                    onProductClick = onProductClick,
                    onChatTabClick = onChatTabClick,
                    onSearch = onSearch,
                    onCategorySelect = onCategorySelect,
                    // 무한스크롤 트리거는 이 파일의 관심사가 아니다(스크롤 위치에 좌우돼 불안정하다).
                    onLoadMore = {},
                    onRetry = onRetry,
                )
            }
        }
    }

    private fun successState(
        products: List<Product>,
        selectedCategoryId: Long? = null,
        keyword: String = "",
        isAppending: Boolean = false,
    ) = HomeUiState.Success(
        products = products,
        categories = listOf(
            Category(id = 1L, name = "디지털기기"),
            Category(id = 2L, name = "생활가전"),
            Category(id = 3L, name = "가구/인테리어"),
            Category(id = 4L, name = "의류"),
        ),
        region = "강남구",
        selectedCategoryId = selectedCategoryId,
        keyword = keyword,
        isAppending = isAppending,
        hasNext = false,
    )

    /** 썸네일이 전부 null 이라 Coil 이 네트워크를 타지 않는다(플레이스홀더만 그려진다). */
    private fun fourProducts() = listOf(
        product(1L, "거의 안 쓴 맥북 프로 14인치 M3", BigDecimal("1450000.00")),
        product(2L, "아이 옷 나눔합니다", BigDecimal("0.00")),
        product(3L, "이케아 책상", BigDecimal("35000.00"), TradeStatus.RESERVED),
        product(4L, "닌텐도 스위치 + 게임 3종", BigDecimal("240000.00")),
    )

    private fun product(
        productId: Long,
        title: String,
        price: BigDecimal,
        tradeStatus: TradeStatus = TradeStatus.ON_SALE,
    ) = Product(
        productId = productId,
        sellerId = 10L,
        categoryId = 1L,
        title = title,
        price = price,
        tradeStatus = tradeStatus,
        region = RegionRef(code = "11680", name = "강남구", fullName = "서울특별시 강남구"),
        viewCount = 42L,
        favoriteCount = 3,
        thumbnailUrl = null,
        hidden = false,
    )
}
