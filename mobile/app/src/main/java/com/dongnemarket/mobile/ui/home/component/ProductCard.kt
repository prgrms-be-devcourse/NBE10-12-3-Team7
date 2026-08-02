package com.dongnemarket.mobile.ui.home.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dongnemarket.mobile.domain.model.Product
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.domain.model.TradeStatus
import com.dongnemarket.mobile.ui.component.NetworkImage
import com.dongnemarket.mobile.ui.component.ProductPrice
import com.dongnemarket.mobile.ui.component.StatusBadge
import com.dongnemarket.mobile.ui.home.TAG_PRODUCT_CARD
import com.dongnemarket.mobile.ui.theme.MarketOnTheme
import java.math.BigDecimal

/**
 * 상품 그리드 카드 1장. 구성은 **썸네일 · 상태배지 · 제목 · 가격 · 지역** 5개다.
 *
 * ⚠️ **"3시간 전" 같은 시간 표기가 없다.** 백엔드 상품 응답에 `createdAt` 이 아예 없어서
 * (계약 §7-10) 만들 수가 없다. `productId` 가 단조증가라 순서 추정은 되지만 그것을 시각처럼
 * 보여 주면 오정보다. 그래서 통상 시간이 들어가는 자리에 [Product.region] 을 넣었다.
 *
 * 가격·배지는 공용 컴포넌트에 맡긴다 — 나눔(0원) 판정과 `BigDecimal` 스케일 처리가
 * 그쪽에 이미 들어 있어서 카드가 다시 구현하면 규칙이 갈라진다.
 */
@Composable
fun ProductCard(
    product: Product,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 노드를 한 겹 감싼 이유: testTag 는 한 노드에 하나만 남기 때문에
    // "카드 전체 개수" 용 공통 태그와 "특정 카드 클릭" 용 개별 태그를 동시에 노출할 수 없다.
    Box(modifier = modifier.testTag(TAG_PRODUCT_CARD)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${TAG_PRODUCT_CARD}_${product.productId}")
                .clickable(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                NetworkImage(
                    url = product.thumbnailUrl,
                    contentDescription = product.title,
                    // 정사각형 고정: 상품 이미지 비율이 제각각이라 높이를 열어 두면
                    // 2열 그리드의 좌우 카드 높이가 어긋난다.
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    shape = MaterialTheme.shapes.medium,
                )
                // 서버가 모르는 거래상태를 보내면 배지가 아예 그려지지 않는다(NONE) → 빈 칸도 안 생긴다.
                StatusBadge(
                    tradeStatus = product.tradeStatus,
                    price = product.price,
                    modifier = Modifier.padding(8.dp),
                )
            }

            Text(
                text = product.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            ProductPrice(price = product.price)

            Text(
                text = product.region.display,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(name = "상품 카드 3종", showBackground = true, widthDp = 200)
@Composable
private fun ProductCardPreview() {
    MarketOnTheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            ProductCard(product = previewProduct(), onClick = {})
            ProductCard(
                product = previewProduct(
                    productId = 2L,
                    title = "아이 옷 정리하다 나온 것들 필요하신 분 나눔합니다",
                    price = BigDecimal("0.00"),
                ),
                onClick = {},
            )
            ProductCard(
                product = previewProduct(
                    productId = 3L,
                    title = "예약중 상품",
                    tradeStatus = TradeStatus.RESERVED,
                ),
                onClick = {},
            )
        }
    }
}

/** 미리보기용 상품. 썸네일은 실제 데모 데이터처럼 null 이라 플레이스홀더가 그려진다. */
private fun previewProduct(
    productId: Long = 1L,
    title: String = "거의 안 쓴 맥북 프로 14인치 M3 팝니다",
    price: BigDecimal = BigDecimal("800000.00"),
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
