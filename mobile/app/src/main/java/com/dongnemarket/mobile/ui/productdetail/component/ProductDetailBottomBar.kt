package com.dongnemarket.mobile.ui.productdetail.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dongnemarket.mobile.ui.theme.MarketOnTheme

/** 테스트가 하트를 찾는 태그. */
const val TAG_DETAIL_FAVORITE = "detail_favorite"

/** 테스트가 '채팅하기' 버튼을 찾는 태그. */
const val TAG_DETAIL_CHAT_BUTTON = "detail_chat_button"

/**
 * 상세 화면 하단 고정 바 — 찜 하트 + 찜 개수 + '채팅하기'.
 *
 * 화면 맨 아래에 붙여 두는 이유: 상세 본문(설명)은 길어서 스크롤되는데,
 * 핵심 행동 두 개는 스크롤 위치와 무관하게 항상 손에 닿아야 한다.
 *
 * 이 Composable 은 **상태를 갖지 않는다**(눌렸다는 사실만 위로 알린다) → Preview·테스트에서 단독으로 돌아간다.
 *
 * @param isFavorite 하트를 채울지. 값은 앱 전역 찜 캐시에서 조합된 것이다(상세 응답에는 없다 — 계약 §7-2).
 * @param isMyProduct 내 상품이면 **찜 하트와 채팅 버튼을 둘 다 비활성**으로 둔다.
 *   서버가 각각 400 `CANNOT_FAVORITE_OWN_PRODUCT` / `CANNOT_CHAT_WITH_SELF` 를 주므로,
 *   실패를 유발하고 안내하는 대신 애초에 누를 수 없게 하는 편이 낫다.
 *
 *   > 하트 비활성화는 2026-08-02 에뮬 검수에서 발견해 추가했다. 그전에는 하트만 눌렸고,
 *   > 누르면 낙관적 갱신이 켜졌다가 서버 400 으로 롤백됐다 — 동작은 안전했지만
 *   > "누를 수 있는데 실패하는" UX였다. 단위·계기 테스트 어느 쪽도 이 조합을 묻지 않아
 *   > 실기 검수 전까지 드러나지 않았다.
 * @param isChatCreating 방 생성 요청 중. 버튼을 잠그고 스피너를 돌려 중복 탭을 막는다.
 */
@Composable
fun ProductDetailBottomBar(
    isFavorite: Boolean,
    favoriteCount: Int,
    isMyProduct: Boolean,
    isChatCreating: Boolean,
    onFavoriteClick: () -> Unit,
    onChatClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    // Scaffold 는 bottomBar 에 시스템 내비게이션 바 여백을 넣어 주지 않는다 →
                    // 이걸 빼면 제스처 바에 버튼이 가린다.
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = onFavoriteClick,
                    // 내 상품은 서버가 찜을 거부한다(400 CANNOT_FAVORITE_OWN_PRODUCT) → 채팅 버튼과 같은 기준으로 잠근다.
                    enabled = !isMyProduct,
                    modifier = Modifier.testTag(TAG_DETAIL_FAVORITE),
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        // 스크린리더가 상태를 읽도록 문구를 바꾼다. 내 상품이면 "왜 못 누르는지"까지 알려 준다.
                        contentDescription = when {
                            isMyProduct -> "내가 등록한 상품은 찜할 수 없어요"
                            isFavorite -> "찜 취소"
                            else -> "찜하기"
                        },
                        tint = when {
                            isMyProduct -> MaterialTheme.colorScheme.outline
                            isFavorite -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                Text(
                    text = favoriteCount.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                VerticalDivider(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .height(24.dp),
                    color = MaterialTheme.colorScheme.outline,
                )

                Button(
                    onClick = onChatClick,
                    enabled = !isMyProduct && !isChatCreating,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TAG_DETAIL_CHAT_BUTTON),
                ) {
                    if (isChatCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(text = if (isMyProduct) "내가 등록한 상품이에요" else "채팅하기")
                }
            }
        }
    }
}

@Preview(name = "하단 바 4가지 상태", showBackground = true)
@Composable
private fun ProductDetailBottomBarPreview() {
    MarketOnTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProductDetailBottomBar(
                isFavorite = false,
                favoriteCount = 0,
                isMyProduct = false,
                isChatCreating = false,
                onFavoriteClick = {},
                onChatClick = {},
            )
            ProductDetailBottomBar(
                isFavorite = true,
                favoriteCount = 13,
                isMyProduct = false,
                isChatCreating = false,
                onFavoriteClick = {},
                onChatClick = {},
            )
            ProductDetailBottomBar(
                isFavorite = false,
                favoriteCount = 2,
                isMyProduct = false,
                isChatCreating = true,
                onFavoriteClick = {},
                onChatClick = {},
            )
            ProductDetailBottomBar(
                isFavorite = false,
                favoriteCount = 5,
                isMyProduct = true,
                isChatCreating = false,
                onFavoriteClick = {},
                onChatClick = {},
            )
        }
    }
}
