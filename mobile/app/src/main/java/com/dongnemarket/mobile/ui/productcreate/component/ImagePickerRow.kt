package com.dongnemarket.mobile.ui.productcreate.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dongnemarket.mobile.ui.productcreate.MAX_PRODUCT_IMAGES
import com.dongnemarket.mobile.ui.theme.MarketOnTheme

/** 테스트가 사진 추가 버튼을 찾는 태그. */
const val TAG_CREATE_ADD_IMAGE = "create_add_image"

/** 테스트가 선택된 사진 칸을 찾는 태그. */
const val TAG_CREATE_IMAGE_ITEM = "create_image_item"

/**
 * 테스트가 사진 줄 자체(스크롤 컨테이너)를 찾는 태그.
 *
 * 줄에 태그가 필요한 이유: [LazyRow] 는 **화면에 보이는 항목만 구성**한다.
 * 96dp 타일 5개는 폰 화면 폭을 넘으므로 마지막 장은 노드로 존재하지도 않는다 →
 * 테스트가 `performScrollToIndex` 로 직접 밀어야 그 항목이 만들어진다.
 */
const val TAG_CREATE_IMAGE_ROW = "create_image_row"

/**
 * 사진 선택 줄 — [추가] 버튼 + 고른 사진 썸네일들.
 *
 * ## 대표 사진을 따로 고르게 하는 이유
 * 서버는 `thumbnailIndex` 로 **어느 사진이 목록에 뜰지**를 받는다.
 * 무조건 첫 장으로 고정할 수도 있지만, 사용자가 원하는 대표를 앞으로 끌어오려면
 * 드래그 재정렬이 필요해진다(구현 비용이 훨씬 크다).
 * "탭해서 대표 지정"이 같은 목적을 훨씬 싸게 이룬다.
 *
 * 그래서 이 컴포넌트에서 **사진 한 장에 탭 영역이 둘**이다:
 *  - 사진 본체 탭 → 대표 지정([onThumbnailSelect])
 *  - 우상단 × 탭 → 삭제([onRemove])
 *
 * @param enabled 업로드 중에는 false. 진행 중에 목록이 바뀌면 이미 보낸 것과 어긋난다.
 */
@Composable
fun ImagePickerRow(
    imageUris: List<String>,
    thumbnailIndex: Int,
    enabled: Boolean,
    onAddClick: () -> Unit,
    onRemove: (Int) -> Unit,
    onThumbnailSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_CREATE_IMAGE_ROW),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 추가 버튼은 항상 맨 앞에 둔다 — 사진이 5장이 되면 사라지므로
        // 뒤에 두면 버튼 위치가 스크롤에 따라 달라져 찾기 어려워진다.
        if (imageUris.size < MAX_PRODUCT_IMAGES) {
            item {
                AddImageTile(
                    count = imageUris.size,
                    enabled = enabled,
                    onClick = onAddClick,
                )
            }
        }

        itemsIndexed(imageUris, key = { _, uri -> uri }) { index, uri ->
            ImageTile(
                uri = uri,
                isThumbnail = index == thumbnailIndex,
                enabled = enabled,
                onSelect = { onThumbnailSelect(index) },
                onRemove = { onRemove(index) },
            )
        }
    }
}

@Composable
private fun AddImageTile(
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(TILE_SIZE)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(TAG_CREATE_ADD_IMAGE),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.AddAPhoto,
            contentDescription = "사진 추가",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // 몇 장까지 되는지를 버튼이 직접 알려 준다 — 5장을 채운 뒤 버튼이 사라지는 이유가 설명된다.
            text = "$count/$MAX_PRODUCT_IMAGES",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ImageTile(
    uri: String,
    isThumbnail: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(TILE_SIZE)
            .aspectRatio(1f)
            .testTag(TAG_CREATE_IMAGE_ITEM),
    ) {
        AsyncImage(
            // content:// URI 를 Coil 이 그대로 읽는다 — 앱이 직접 디코딩할 필요가 없다.
            model = uri,
            contentDescription = if (isThumbnail) "대표 사진" else "사진 (탭하면 대표로 지정)",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .border(
                    // 대표 사진만 테두리로 구분한다. 배지(아래)와 이중으로 표시하는 이유는
                    // 색 대비를 구분하지 못하는 사용자도 배지 글자로 알 수 있게 하기 위해서다.
                    width = if (isThumbnail) 2.dp else 1.dp,
                    color = if (isThumbnail) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable(enabled = enabled, onClick = onSelect),
        )

        if (isThumbnail) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(topStart = 12.dp, bottomEnd = 8.dp),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Text(
                    text = "대표",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                .clickable(enabled = enabled, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "사진 삭제",
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 한 줄에 3.5개쯤 보이는 크기. 더 크면 5장을 훑는 데 스크롤이 길어진다. */
private val TILE_SIZE = 96.dp

@Preview(name = "사진 선택 줄", showBackground = true, widthDp = 400)
@Composable
private fun ImagePickerRowPreview() {
    MarketOnTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ImagePickerRow(
                imageUris = emptyList(),
                thumbnailIndex = 0,
                enabled = true,
                onAddClick = {},
                onRemove = {},
                onThumbnailSelect = {},
            )
            ImagePickerRow(
                imageUris = listOf("a", "b", "c"),
                thumbnailIndex = 1,
                enabled = true,
                onAddClick = {},
                onRemove = {},
                onThumbnailSelect = {},
            )
        }
    }
}
