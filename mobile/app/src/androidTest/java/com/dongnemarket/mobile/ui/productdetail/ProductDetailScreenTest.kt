package com.dongnemarket.mobile.ui.productdetail

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dongnemarket.mobile.domain.model.ProductDetail
import com.dongnemarket.mobile.domain.model.TradeStatus
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.ui.productdetail.component.TAG_DETAIL_CHAT_BUTTON
import com.dongnemarket.mobile.ui.productdetail.component.TAG_DETAIL_FAVORITE
import com.dongnemarket.mobile.ui.theme.MarketOnTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * 상품 상세 화면(stateless 본체 [ProductDetailContent])의 Compose UI 테스트.
 *
 * **Hilt·ViewModel 을 쓰지 않는다.** 상세 화면은 이동 의도를 람다로만 알리고 상태를 파라미터로 받도록
 * 설계돼 있어서, 화면을 단독으로 띄우고 "이 상태를 주면 이렇게 보인다"만 검증할 수 있다.
 * (연결 버전 [ProductDetailScreen] 은 `hiltViewModel()` 기본값 때문에 계기 테스트에서 단독으로 못 띄운다.)
 */
@RunWith(AndroidJUnit4::class)
class ProductDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------------
    // 테스트가 쓰는 상품. 가격 "800000.00" 은 서버 GET 응답의 실제 스케일이다(POST 응답은 "800000").
    // ------------------------------------------------------------------
    private fun product(
        price: String = "800000.00",
        tradeStatus: TradeStatus = TradeStatus.ON_SALE,
        imageUrls: List<String> = emptyList(),
    ) = ProductDetail(
        productId = 12L,
        sellerId = 7L,
        sellerNickname = "동네주민",
        categoryId = 1L,
        title = "아이패드 프로 11인치 5세대",
        description = "케이스 씌워 사용해서 기스 없습니다.",
        price = BigDecimal(price),
        tradeStatus = tradeStatus,
        region = RegionRef(code = "11680", name = "강남구", fullName = "서울특별시 강남구"),
        viewCount = 1_204L,
        favoriteCount = 13,
        thumbnailUrl = null,
        imageUrls = imageUrls,
        hidden = false,
    )

    /** 상세 화면을 주어진 상태로 띄운다. 클릭 람다는 호출자가 필요할 때만 넘긴다. */
    private fun 상세화면을_띄운다(
        state: ProductDetailUiState,
        onFavoriteClick: () -> Unit = {},
        onChatClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            MarketOnTheme {
                ProductDetailContent(
                    state = state,
                    onBackClick = {},
                    onFavoriteClick = onFavoriteClick,
                    onChatClick = onChatClick,
                    onRetry = {},
                )
            }
        }
    }

    // ==================================================================
    // 1. 성공 상태 — 제목·가격·배지
    // ==================================================================

    @Test
    fun `상세_조회에_성공하면_상품_제목이_보인다`() {
        // Given: 판매중인 아이패드 상세를 받아 온 상태
        val state = ProductDetailUiState.Success(
            product = product(),
            isFavorite = false,
            favoriteCount = 13,
            categoryName = "디지털기기",
        )

        // When: 상세 화면을 연다
        상세화면을_띄운다(state)

        // Then: 제목 자리에 서버가 준 제목이 그대로 찍힌다
        composeRule.onNodeWithTag(TAG_DETAIL_TITLE)
            .performScrollTo()
            .assertTextEquals("아이패드 프로 11인치 5세대")
    }

    @Test
    fun `가격_800000_00_은_800_000원_으로_서식이_붙어_보인다`() {
        // Given: 서버 GET 응답 스케일 그대로인 가격("800000.00")
        val state = ProductDetailUiState.Success(
            product = product(price = "800000.00"),
            isFavorite = false,
            favoriteCount = 13,
        )

        // When
        상세화면을_띄운다(state)

        // Then: 소수부는 버리고 세 자리 구분 + "원" 이 붙는다(800,000.00원 이 아니다)
        composeRule.onNodeWithTag(TAG_DETAIL_PRICE)
            .performScrollTo()
            .assertTextEquals("800,000원")
    }

    @Test
    fun `판매중_상품이면_판매중_배지가_보인다`() {
        // Given: tradeStatus = ON_SALE 이고 가격이 0원보다 큰 상품
        val state = ProductDetailUiState.Success(
            product = product(tradeStatus = TradeStatus.ON_SALE, price = "800000.00"),
            isFavorite = false,
            favoriteCount = 13,
        )

        // When
        상세화면을_띄운다(state)

        // Then
        composeRule.onNodeWithText("판매중").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `예약중_상품이면_예약중_배지가_보인다`() {
        // Given: 같은 상품이 예약 상태로 바뀐 경우
        val state = ProductDetailUiState.Success(
            product = product(tradeStatus = TradeStatus.RESERVED),
            isFavorite = false,
            favoriteCount = 13,
        )

        // When
        상세화면을_띄운다(state)

        // Then: 배지 문구가 거래 상태를 따라간다
        composeRule.onNodeWithText("예약중").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `0원_상품이면_가격_자리에_나눔_이_보인다`() {
        // Given: 서버에 '나눔' 상태는 없다 — ON_SALE + 0원이 나눔이라는 앱 측 규약(계약 §6.2)
        val state = ProductDetailUiState.Success(
            product = product(price = "0.00", tradeStatus = TradeStatus.ON_SALE),
            isFavorite = false,
            favoriteCount = 0,
        )

        // When
        상세화면을_띄운다(state)

        // Then: "0원" 이 아니라 "나눔" 으로 바뀐다
        composeRule.onNodeWithTag(TAG_DETAIL_PRICE)
            .performScrollTo()
            .assertTextEquals("나눔")
    }

    // ==================================================================
    // 2. 찜 하트 — 상태에 따라 아이콘 설명이 달라진다
    // ==================================================================

    @Test
    fun `이미_찜한_상품이면_하트가_찜_취소_상태로_보인다`() {
        // Given: 전역 찜 캐시에서 조합된 isFavorite = true
        val state = ProductDetailUiState.Success(
            product = product(),
            isFavorite = true,
            favoriteCount = 14,
        )

        // When
        상세화면을_띄운다(state)

        // Then: 채워진 하트 + 스크린리더가 읽는 설명이 "찜 취소" 다
        composeRule.onNodeWithTag(TAG_DETAIL_FAVORITE)
            .assertContentDescriptionEquals("찜 취소")
    }

    @Test
    fun `찜하지_않은_상품이면_하트가_찜하기_상태로_보인다`() {
        // Given
        val state = ProductDetailUiState.Success(
            product = product(),
            isFavorite = false,
            favoriteCount = 13,
        )

        // When
        상세화면을_띄운다(state)

        // Then: 빈 하트 + 설명이 "찜하기" 다(같은 버튼이 상태에 따라 다르게 읽힌다)
        composeRule.onNodeWithTag(TAG_DETAIL_FAVORITE)
            .assertContentDescriptionEquals("찜하기")
    }

    @Test
    fun `하트를_누르면_onFavoriteClick_이_화면_밖으로_전달된다`() {
        // Given: 찜하지 않은 상품 상세
        var favoriteClickCount = 0
        상세화면을_띄운다(
            state = ProductDetailUiState.Success(
                product = product(),
                isFavorite = false,
                favoriteCount = 13,
            ),
            onFavoriteClick = { favoriteClickCount++ },
        )

        // When: 하단 바의 하트를 한 번 누른다
        composeRule.onNodeWithTag(TAG_DETAIL_FAVORITE).performClick()

        // Then
        assertEquals(1, favoriteClickCount)
    }

    // ==================================================================
    // 3. 채팅하기 버튼
    // ==================================================================

    @Test
    fun `채팅하기를_누르면_onChatClick_이_화면_밖으로_전달된다`() {
        // Given: 남의 상품(= 채팅 가능) 상세
        var chatClickCount = 0
        상세화면을_띄운다(
            state = ProductDetailUiState.Success(
                product = product(),
                isFavorite = false,
                favoriteCount = 13,
                isMyProduct = false,
            ),
            onChatClick = { chatClickCount++ },
        )

        // When
        composeRule.onNodeWithTag(TAG_DETAIL_CHAT_BUTTON).performClick()

        // Then
        assertEquals(1, chatClickCount)
    }

    @Test
    fun `내가_등록한_상품이면_채팅하기_버튼이_비활성이다`() {
        // Given: 내 memberId == product.sellerId 로 판정된 상태.
        //        서버는 이 경우 400 CANNOT_CHAT_WITH_SELF 를 주므로 애초에 못 누르게 한다.
        val state = ProductDetailUiState.Success(
            product = product(),
            isFavorite = false,
            favoriteCount = 13,
            isMyProduct = true,
        )

        // When
        상세화면을_띄운다(state)

        // Then
        composeRule.onNodeWithTag(TAG_DETAIL_CHAT_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun `내가_등록한_상품이면_찜_하트도_비활성이다`() {
        // Given: 서버가 400 CANNOT_FAVORITE_OWN_PRODUCT 로 거부하는 조합.
        //        2026-08-02 에뮬 검수에서 발견 — 그전에는 하트만 눌렸고 낙관적 갱신이
        //        켜졌다가 서버 400 으로 롤백됐다. 동작은 안전했지만 누를 수 있는데 실패하는 UX였다.
        val state = ProductDetailUiState.Success(
            product = product(),
            isFavorite = false,
            favoriteCount = 13,
            isMyProduct = true,
        )

        // When
        상세화면을_띄운다(state)

        // Then: 채팅 버튼과 같은 기준으로 잠겨야 한다
        composeRule.onNodeWithTag(TAG_DETAIL_FAVORITE).assertIsNotEnabled()
    }

    @Test
    fun `내가_등록한_상품이면_버튼_문구가_내가_등록한_상품이에요_로_바뀐다`() {
        // Given
        val state = ProductDetailUiState.Success(
            product = product(),
            isFavorite = false,
            favoriteCount = 13,
            isMyProduct = true,
        )

        // When
        상세화면을_띄운다(state)

        // Then: 왜 못 누르는지 버튼 자신이 설명한다("채팅하기" 가 회색으로만 남지 않는다)
        composeRule.onNodeWithText("내가 등록한 상품이에요").assertIsDisplayed()
    }

    @Test
    fun `방_생성_요청_중이면_채팅하기_버튼이_비활성이라_두_번_눌리지_않는다`() {
        // Given: '채팅하기' 를 눌러 POST /api/chat-rooms 가 진행 중인 상태
        val state = ProductDetailUiState.Success(
            product = product(),
            isFavorite = false,
            favoriteCount = 13,
            isChatCreating = true,
        )

        // When
        상세화면을_띄운다(state)

        // Then
        composeRule.onNodeWithTag(TAG_DETAIL_CHAT_BUTTON).assertIsNotEnabled()
    }

    // ==================================================================
    // 4. 데모/시드 데이터 방어 — 이미지가 한 장도 없는 상품
    // ==================================================================

    @Test
    fun `이미지가_한_장도_없는_상품도_크래시_없이_상세가_그려진다`() {
        // Given: 시드·데모 데이터의 실제 모습 — imageUrls 가 빈 리스트다(계약 §7-6).
        //        페이저는 페이지 수 0을 그릴 수 없으므로 플레이스홀더 한 장으로 대체돼야 한다.
        val state = ProductDetailUiState.Success(
            product = product(imageUrls = emptyList()),
            isFavorite = false,
            favoriteCount = 13,
        )

        // When
        상세화면을_띄운다(state)

        // Then: 화면이 죽지 않고 본문(제목)까지 정상으로 그려진다
        composeRule.onNodeWithTag(TAG_DETAIL_TITLE)
            .performScrollTo()
            .assertTextEquals("아이패드 프로 11인치 5세대")
    }

    @Test
    fun `이미지가_여러_장이면_페이저로_바뀌어도_본문은_그대로_그려진다`() {
        // Given: 이미지 3장(페이저 분기)
        val state = ProductDetailUiState.Success(
            product = product(
                imageUrls = listOf(
                    "/api/products/images/a.jpg",
                    "/api/products/images/b.jpg",
                    "/api/products/images/c.jpg",
                ),
            ),
            isFavorite = false,
            favoriteCount = 13,
        )

        // When
        상세화면을_띄운다(state)

        // Then
        composeRule.onNodeWithTag(TAG_DETAIL_TITLE)
            .performScrollTo()
            .assertTextEquals("아이패드 프로 11인치 5세대")
    }

    // ==================================================================
    // 5. 에러 상태 — 삭제·거래완료 상품은 '정상적인' 404 다
    // ==================================================================

    @Test
    fun `에러_상태면_사용자용_에러_문구가_보인다`() {
        // Given: 404 를 AppError.userMessage 로 바꿔 담은 상태
        val state = ProductDetailUiState.Error("삭제되었거나 거래가 끝난 상품이에요.")

        // When
        상세화면을_띄운다(state)

        // Then
        composeRule.onNodeWithText("삭제되었거나 거래가 끝난 상품이에요.").assertIsDisplayed()
    }

    @Test
    fun `에러_상태면_하단_찜_채팅_바가_아예_그려지지_않는다`() {
        // Given: 상품을 못 받아 온 상태 — 찜하거나 채팅할 대상 자체가 없다
        val state = ProductDetailUiState.Error("삭제되었거나 거래가 끝난 상품이에요.")

        // When
        상세화면을_띄운다(state)

        // Then: 비활성 버튼이 남는 것이 아니라 하단 바가 통째로 사라진다
        composeRule.onNodeWithTag(TAG_DETAIL_CHAT_BUTTON).assertDoesNotExist()
    }
}
