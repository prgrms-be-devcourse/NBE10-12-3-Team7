package com.dongnemarket.mobile.ui.productcreate.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dongnemarket.mobile.ui.theme.MarketOnTheme

/**
 * 값 하나를 고르는 칩 묶음. 카테고리와 동네가 같은 모양을 쓴다.
 *
 * ## 왜 드롭다운이 아니라 칩인가
 * 카테고리는 8개, 동네는 최대 2개다. 이 정도면 **전부 펼쳐 보이는 편이** 드롭다운보다 빠르다 —
 * 드롭다운은 "열고 → 찾고 → 고르고" 세 동작이지만 칩은 한 번 탭이면 끝난다.
 * 선택지가 수십 개로 늘면 그때 검색 가능한 목록으로 바꿔야 한다.
 *
 * [FlowRow] 를 쓰는 이유: 칩 개수와 글자 길이를 서버가 정하므로 몇 줄이 될지 앱이 모른다.
 * 가로 스크롤(`LazyRow`)로 두면 오른쪽에 가려진 칩을 사용자가 못 볼 수 있는데,
 * **필수 입력**에서 선택지가 숨는 건 위험하다. 홈의 카테고리 칩이 가로 스크롤인 것과 다른 판단이며,
 * 거기서는 칩이 필터라 놓쳐도 손해가 없다.
 *
 * @param options `(값, 표시할 이름)` 목록. 값의 타입은 카테고리(Long)·동네(String)가 달라 제네릭이다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SelectionChipGroup(
    options: List<Pair<T, String>>,
    selected: T?,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled,
                label = { Text(text = label, style = MaterialTheme.typography.labelLarge) },
                // 선택 여부를 색뿐 아니라 체크 표시로도 알린다(색만으로 구분하면 접근성 문제).
                leadingIcon = if (value == selected) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null, // 선택 상태는 FilterChip 이 이미 알린다
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Preview(name = "선택 칩", showBackground = true, widthDp = 400)
@Composable
private fun SelectionChipGroupPreview() {
    MarketOnTheme {
        SelectionChipGroup(
            options = listOf(
                1L to "디지털기기",
                2L to "생활가전",
                3L to "가구/인테리어",
                4L to "의류",
                5L to "도서",
            ),
            selected = 3L,
            enabled = true,
            onSelect = {},
        )
    }
}
