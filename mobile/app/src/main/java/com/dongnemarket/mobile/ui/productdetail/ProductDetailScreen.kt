package com.dongnemarket.mobile.ui.productdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dongnemarket.mobile.domain.model.ProductDetail
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.domain.model.TradeStatus
import com.dongnemarket.mobile.ui.component.ErrorView
import com.dongnemarket.mobile.ui.component.LoadingView
import com.dongnemarket.mobile.ui.component.ProductPrice
import com.dongnemarket.mobile.ui.component.StatusBadge
import com.dongnemarket.mobile.ui.productdetail.component.ProductDetailBottomBar
import com.dongnemarket.mobile.ui.productdetail.component.ProductImagePager
import com.dongnemarket.mobile.ui.productdetail.component.SellerRow
import com.dongnemarket.mobile.ui.theme.MarketOnTheme
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** 테스트가 제목을 찾는 태그. */
const val TAG_DETAIL_TITLE = "detail_title"

/** 테스트가 가격을 찾는 태그. */
const val TAG_DETAIL_PRICE = "detail_price"

/**
 * 상품 상세 화면(연결 버전). ViewModel 을 붙이고 일회성 사건(스낵바·채팅방 이동)을 처리한다.
 *
 * **NavController 를 받지 않는다.** 이동 의도는 [onBackClick]·[onChatCreated] 람다로만 알린다 →
 * 화면이 네비게이션 구조를 모르게 되어 Preview·UI 테스트에서 단독으로 띄울 수 있다.
 * 어느 상품인지(`productId`)도 파라미터로 받지 않는다 — ViewModel 이 `SavedStateHandle` 에서 직접 꺼낸다.
 *
 * @param onChatCreated 채팅방이 확보됐을 때(신규 생성 또는 기존 방 재사용) `roomId` 를 들고 호출된다.
 */
@Composable
fun ProductDetailScreen(
    onBackClick: () -> Unit,
    onChatCreated: (roomId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProductDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 방 생성 성공은 '한 번 일어나고 끝나는 사건' 이라 상태가 아니라 이벤트로 받는다.
    // 상태 필드로 두면 화면 회전 후 상태를 다시 읽는 순간 채팅방으로 또 이동한다.
    LaunchedEffect(Unit) {
        viewModel.chatRoomEvent.collect { roomId -> onChatCreated(roomId) }
    }

    val message = (uiState as? ProductDetailUiState.Success)?.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            // 띄운 메시지는 즉시 비운다 → 같은 문구가 리컴포지션마다 다시 뜨지 않는다.
            viewModel.consumeMessage()
        }
    }

    ProductDetailContent(
        state = uiState,
        onBackClick = onBackClick,
        onFavoriteClick = viewModel::onFavoriteClick,
        onChatClick = viewModel::onChatClick,
        onRetry = viewModel::retry,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * 상품 상세 화면(순수 버전). ViewModel 없이 상태를 직접 받아 그리기만 한다 → `@Preview` 로 검수할 수 있다.
 *
 * 골격: 상단 뒤로가기 → 이미지 페이저 → 배지·제목·가격 → 카테고리·지역·조회수 → 설명 →
 * 구분선 → 판매자 → 하단 고정 바(찜·채팅하기).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailContent(
    state: ProductDetailUiState,
    onBackClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onChatClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            // 하단 바는 성공 상태에서만 의미가 있다(로딩·에러 화면에서 찜·채팅은 누를 대상이 없다).
            if (state is ProductDetailUiState.Success) {
                ProductDetailBottomBar(
                    isFavorite = state.isFavorite,
                    favoriteCount = state.favoriteCount,
                    isMyProduct = state.isMyProduct,
                    isChatCreating = state.isChatCreating,
                    onFavoriteClick = onFavoriteClick,
                    onChatClick = onChatClick,
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        when (state) {
            ProductDetailUiState.Loading -> LoadingView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is ProductDetailUiState.Error -> ErrorView(
                message = state.message,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onRetry = onRetry,
            )

            is ProductDetailUiState.Success -> ProductDetailBody(
                state = state,
                contentPadding = innerPadding,
            )
        }
    }
}

/** 성공 상태의 본문. 설명이 길어질 수 있어 세로 스크롤을 둔다. */
@Composable
private fun ProductDetailBody(
    state: ProductDetailUiState.Success,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val product = state.product

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        ProductImagePager(
            imageUrls = product.imageUrls,
            contentDescription = product.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 배지 종류 판정(나눔 = 0원 등)은 공용 컴포넌트가 하므로 화면에서 when 을 다시 쓰지 않는다.
            StatusBadge(tradeStatus = product.tradeStatus, price = product.price)

            Text(
                text = product.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(TAG_DETAIL_TITLE),
            )

            ProductPrice(
                price = product.price,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(TAG_DETAIL_PRICE),
            )

            // ⚠ 여기에 '작성 시각'("3시간 전")을 넣을 수 없다 — 상품 응답에 시간 필드가 아예 없다(계약 §7-10).
            //   그 자리를 지역 이름으로 채우는 것이 §8-3 의 결정이다.
            Text(
                text = metaLine(
                    categoryName = state.categoryName,
                    region = product.region.display,
                    viewCount = product.viewCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (product.description.isNotBlank()) {
                Text(
                    text = product.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        SellerRow(
            nickname = product.sellerNickname,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
}

/**
 * "디지털기기 · 서울 강남구 · 조회 1,204" 한 줄을 만든다.
 *
 * 값이 없는 조각은 넣지 않는다(카테고리 이름을 못 찾았거나 지역이 빈 문자열인 경우) —
 * 빈 조각을 그대로 이으면 " ·  · 조회 3" 처럼 구분점만 남는다.
 */
private fun metaLine(categoryName: String?, region: String, viewCount: Long): String {
    val viewCountText = NumberFormat.getIntegerInstance(Locale.KOREA).format(viewCount)
    return listOfNotNull(
        categoryName?.takeIf { it.isNotBlank() },
        region.takeIf { it.isNotBlank() },
        "조회 $viewCountText",
    ).joinToString(" · ")
}

// ────────────────────────────── Preview ──────────────────────────────

/** Preview 전용 가짜 상세. 가격을 `"800000.00"` 으로 둔 것은 서버 GET 응답의 실제 스케일이다. */
private fun previewProduct(
    imageCount: Int = 0,
    price: String = "800000.00",
    tradeStatus: TradeStatus = TradeStatus.ON_SALE,
) = ProductDetail(
    productId = 12L,
    sellerId = 7L,
    sellerNickname = "동네주민",
    categoryId = 1L,
    title = "아이패드 프로 11인치 5세대 (스페이스 그레이)",
    description = "작년에 구매했고 케이스 씌워 사용해서 기스 없습니다.\n애플펜슬 2세대 포함이고 직거래만 가능합니다.",
    price = BigDecimal(price),
    tradeStatus = tradeStatus,
    region = RegionRef(code = "11680", name = "강남구", fullName = "서울특별시 강남구"),
    viewCount = 1_204L,
    favoriteCount = 13,
    thumbnailUrl = null,
    imageUrls = List(imageCount) { "/api/products/images/sample-$it.jpg" },
    hidden = false,
)

@Preview(name = "상세 · 성공(이미지 없음)", showBackground = true, heightDp = 900)
@Composable
private fun ProductDetailSuccessPreview() {
    MarketOnTheme {
        ProductDetailContent(
            state = ProductDetailUiState.Success(
                product = previewProduct(imageCount = 0),
                isFavorite = false,
                favoriteCount = 13,
                categoryName = "디지털기기",
            ),
            onBackClick = {},
            onFavoriteClick = {},
            onChatClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "상세 · 찜함 + 이미지 3장", showBackground = true, heightDp = 900)
@Composable
private fun ProductDetailFavoritedPreview() {
    MarketOnTheme {
        ProductDetailContent(
            state = ProductDetailUiState.Success(
                product = previewProduct(imageCount = 3, tradeStatus = TradeStatus.RESERVED),
                isFavorite = true,
                favoriteCount = 14,
                categoryName = "디지털기기",
            ),
            onBackClick = {},
            onFavoriteClick = {},
            onChatClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "상세 · 나눔 + 내 상품", showBackground = true, heightDp = 900)
@Composable
private fun ProductDetailMyGiveawayPreview() {
    MarketOnTheme {
        ProductDetailContent(
            state = ProductDetailUiState.Success(
                product = previewProduct(imageCount = 1, price = "0.00"),
                isFavorite = false,
                favoriteCount = 0,
                // 카테고리 이름을 못 찾은 경우(null) → 메타 줄에서 그 조각이 빠진다.
                categoryName = null,
                isMyProduct = true,
            ),
            onBackClick = {},
            onFavoriteClick = {},
            onChatClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "상세 · 로딩", showBackground = true, heightDp = 480)
@Composable
private fun ProductDetailLoadingPreview() {
    MarketOnTheme {
        ProductDetailContent(
            state = ProductDetailUiState.Loading,
            onBackClick = {},
            onFavoriteClick = {},
            onChatClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "상세 · 404(삭제·거래완료)", showBackground = true, heightDp = 480)
@Composable
private fun ProductDetailErrorPreview() {
    MarketOnTheme {
        ProductDetailContent(
            state = ProductDetailUiState.Error("삭제되었거나 거래가 끝난 상품이에요."),
            onBackClick = {},
            onFavoriteClick = {},
            onChatClick = {},
            onRetry = {},
        )
    }
}
