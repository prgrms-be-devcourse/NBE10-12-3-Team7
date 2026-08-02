package com.dongnemarket.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dongnemarket.mobile.domain.model.Category
import com.dongnemarket.mobile.domain.model.Product
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.domain.model.TradeStatus
import com.dongnemarket.mobile.ui.component.EmptyView
import com.dongnemarket.mobile.ui.component.ErrorView
import com.dongnemarket.mobile.ui.component.LoadingView
import com.dongnemarket.mobile.ui.component.MarketOnBottomBar
import com.dongnemarket.mobile.ui.component.MarketOnTab
import com.dongnemarket.mobile.ui.home.component.CategoryChipRow
import com.dongnemarket.mobile.ui.home.component.HomeHeroSection
import com.dongnemarket.mobile.ui.home.component.HomeSearchBar
import com.dongnemarket.mobile.ui.home.component.ProductCard
import com.dongnemarket.mobile.ui.theme.MarketOnTheme
import java.math.BigDecimal

// UI 테스트가 요소를 찾는 이름표. 화면과 테스트가 같은 상수를 보게 하려고 문자열을 한곳에 둔다.
internal const val TAG_SEARCH = "home_search"
internal const val TAG_CATEGORY_CHIPS = "home_category_chips"
internal const val TAG_PRODUCT_GRID = "home_product_grid"
internal const val TAG_PRODUCT_CARD = "home_product_card"

/** 그리드 끝에서 몇 칸 남았을 때 다음 페이지를 미리 부를지. 스크롤이 멈추기 전에 도착하게 하는 여유분. */
private const val LOAD_MORE_THRESHOLD = 4

/**
 * 홈(상품목록) 화면.
 *
 * `NavController` 를 받지 않는다 — 이동은 [onProductClick]·[onChatTabClick] 람다로 "의도"만 알린다.
 * 그래야 이 화면이 네비게이션 그래프를 모르는 순수 UI 가 되고, Compose 테스트에서 단독으로 띄울 수 있다.
 *
 * @param onProductClick 상품 카드를 눌렀다 — 상세로 이동해 달라(인자는 `productId`).
 * @param onChatTabClick 하단 채팅 탭을 눌렀다 — 채팅 목록으로 이동해 달라.
 */
@Composable
fun HomeScreen(
    onProductClick: (Long) -> Unit,
    onChatTabClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeContent(
        state = uiState,
        onProductClick = onProductClick,
        onChatTabClick = onChatTabClick,
        onSearch = viewModel::onSearch,
        onCategorySelect = viewModel::onCategorySelect,
        onLoadMore = viewModel::onLoadMore,
        onRetry = viewModel::onRetry,
        modifier = modifier,
    )
}

/**
 * ViewModel 없이 상태만 받아 그리는 본체. [HomeScreen] 이 얇은 껍데기인 이유가 이것이다 —
 * `@Preview` 와 UI 테스트가 Hilt·네트워크 없이 화면을 검수할 수 있다.
 *
 * ⚠️ **상품 등록 FAB 이 없다.** Phase 1 에 등록 화면이 없어서 누르면 아무 일도 안 나는 버튼이 되고,
 * 그건 사용자가 앱이 고장 났다고 느끼는 가장 빠른 길이다. 등록 화면이 생기면 여기 Scaffold 에
 * `floatingActionButton` 을 붙이면 된다.
 */
@Composable
fun HomeContent(
    state: HomeUiState,
    onProductClick: (Long) -> Unit,
    onChatTabClick: () -> Unit,
    onSearch: (String) -> Unit,
    onCategorySelect: (Long?) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        // 본문은 흰 바탕, 히어로만 크림색으로 띄운다(테마 기본 배경이 크림이라 명시적으로 지정).
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            MarketOnBottomBar(
                selected = MarketOnTab.HOME,
                onSelect = { tab ->
                    when (tab) {
                        MarketOnTab.CHAT -> onChatTabClick()
                        // 홈은 이미 이 화면이고, 찜·내정보는 enabled=false 라 호출되지 않는다.
                        else -> Unit
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                HomeUiState.Loading -> LoadingView()

                is HomeUiState.Error -> ErrorView(message = state.message) { onRetry() }

                is HomeUiState.Success -> HomeGrid(
                    state = state,
                    onProductClick = onProductClick,
                    onSearch = onSearch,
                    onCategorySelect = onCategorySelect,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

/**
 * 히어로·검색바·칩·상품그리드를 **하나의 스크롤 컨테이너**에 담는다.
 *
 * 헤더들을 그리드 밖(Column)에 두면 상품만 스크롤되고 히어로가 화면 위에 붙어 있어
 * 작은 화면에서 상품이 보이는 영역이 반토막 난다. `LazyVerticalGrid` 는 `span` 으로 한 줄 전체를
 * 차지하는 항목을 섞을 수 있어서 헤더를 그리드 항목으로 넣었다.
 */
@Composable
private fun HomeGrid(
    state: HomeUiState.Success,
    onProductClick: (Long) -> Unit,
    onSearch: (String) -> Unit,
    onCategorySelect: (Long?) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()

    // 무한스크롤 트리거. derivedStateOf 로 감싸는 이유: 스크롤 픽셀이 바뀔 때마다가 아니라
    // "끝에 닿았다/안 닿았다" 가 바뀔 때만 리컴포지션·이펙트가 돌게 한다.
    val shouldLoadMore by remember(gridState) {
        derivedStateOf {
            val layout = gridState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            lastVisible >= layout.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }

    // products.size 를 key 에 넣은 이유: 한 페이지가 화면을 다 못 채우면
    // 조건이 계속 true 라서 값 변화가 없고, 그러면 이펙트가 다시 돌지 않아 로딩이 멈춰 버린다.
    // 중복 요청은 ViewModel 의 isAppending 가드가 막으므로 여기서는 넉넉히 불러도 안전하다.
    LaunchedEffect(shouldLoadMore, state.products.size) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_PRODUCT_GRID),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1) 히어로 — 브랜드 카피 + 내 동네. 숫자 통계는 넣지 않는다(통계 API 없음).
        item(span = { GridItemSpan(maxLineSpan) }) {
            HomeHeroSection(region = state.region)
        }

        // 2) 검색바 — 확정(IME 검색) 시에만 조회한다.
        item(span = { GridItemSpan(maxLineSpan) }) {
            HomeSearchBar(
                keyword = state.keyword,
                onSearch = onSearch,
                modifier = Modifier.testTag(TAG_SEARCH),
            )
        }

        // 3) 카테고리 칩 — 조회 실패 시 "전체" 칩만 남고 화면은 살아 있다.
        item(span = { GridItemSpan(maxLineSpan) }) {
            CategoryChipRow(
                categories = state.categories,
                selectedCategoryId = state.selectedCategoryId,
                onSelect = onCategorySelect,
                modifier = Modifier.testTag(TAG_CATEGORY_CHIPS),
            )
        }

        if (state.products.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // isAppending == true 면 "결과 0건" 이 아니라 "아직 받는 중" 이다.
                // 필터를 바꾼 직후가 이 경로 — 여기서 EmptyView 를 그리면 없는 사실을 단정하게 된다.
                if (state.isAppending) {
                    LoadingView(modifier = Modifier.padding(top = 32.dp))
                } else {
                    EmptyView(
                        message = if (state.isFiltering) {
                            "조건에 맞는 상품이 없어요.\n검색어나 카테고리를 바꿔 보세요."
                        } else {
                            "아직 등록된 상품이 없어요."
                        },
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }
        } else {
            items(items = state.products, key = { it.productId }) { product ->
                ProductCard(
                    product = product,
                    onClick = { onProductClick(product.productId) },
                )
            }

            // 다음 페이지 로딩 표시. 검색·카테고리 모드에서는 hasNext 가 항상 false 라 여기까지 오지 않는다.
            if (state.isAppending) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LoadingView()
                }
            }
        }
    }
}

// ──────────────────────────── 미리보기 ────────────────────────────

@Preview(name = "홈 · 목록", showBackground = true, heightDp = 900)
@Composable
private fun HomeContentPreview() {
    MarketOnTheme {
        HomeContent(
            state = previewSuccess(),
            onProductClick = {},
            onChatTabClick = {},
            onSearch = {},
            onCategorySelect = {},
            onLoadMore = {},
            onRetry = {},
        )
    }
}

@Preview(name = "홈 · 동네 미설정 + 카테고리 실패", showBackground = true, heightDp = 900)
@Composable
private fun HomeContentNoRegionPreview() {
    MarketOnTheme {
        HomeContent(
            state = previewSuccess().copy(region = null, categories = emptyList()),
            onProductClick = {},
            onChatTabClick = {},
            onSearch = {},
            onCategorySelect = {},
            onLoadMore = {},
            onRetry = {},
        )
    }
}

@Preview(name = "홈 · 검색 결과 0건", showBackground = true, heightDp = 900)
@Composable
private fun HomeContentEmptyPreview() {
    MarketOnTheme {
        HomeContent(
            state = previewSuccess().copy(products = emptyList(), keyword = "냉장고"),
            onProductClick = {},
            onChatTabClick = {},
            onSearch = {},
            onCategorySelect = {},
            onLoadMore = {},
            onRetry = {},
        )
    }
}

@Preview(name = "홈 · 첫 로딩", showBackground = true, heightDp = 900)
@Composable
private fun HomeContentLoadingPreview() {
    MarketOnTheme {
        HomeContent(
            state = HomeUiState.Loading,
            onProductClick = {},
            onChatTabClick = {},
            onSearch = {},
            onCategorySelect = {},
            onLoadMore = {},
            onRetry = {},
        )
    }
}

@Preview(name = "홈 · 목록 실패", showBackground = true, heightDp = 900)
@Composable
private fun HomeContentErrorPreview() {
    MarketOnTheme {
        HomeContent(
            state = HomeUiState.Error("네트워크 연결을 확인해 주세요."),
            onProductClick = {},
            onChatTabClick = {},
            onSearch = {},
            onCategorySelect = {},
            onLoadMore = {},
            onRetry = {},
        )
    }
}

/** 미리보기용 정상 상태. 썸네일은 실제 데모 데이터처럼 전부 null 이라 플레이스홀더가 보인다. */
private fun previewSuccess() = HomeUiState.Success(
    products = listOf(
        previewProduct(1L, "거의 안 쓴 맥북 프로 14인치 M3", BigDecimal("1450000.00")),
        previewProduct(2L, "아이 옷 나눔합니다", BigDecimal("0.00")),
        previewProduct(3L, "이케아 책상 (예약중)", BigDecimal("35000.00"), TradeStatus.RESERVED),
        previewProduct(4L, "닌텐도 스위치 + 게임 3종", BigDecimal("240000.00")),
    ),
    categories = listOf(
        Category(1L, "디지털기기"),
        Category(2L, "생활가전"),
        Category(3L, "가구/인테리어"),
        Category(4L, "의류"),
    ),
    region = "강남구",
    selectedCategoryId = null,
    keyword = "",
    isAppending = false,
    hasNext = true,
)

private fun previewProduct(
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
