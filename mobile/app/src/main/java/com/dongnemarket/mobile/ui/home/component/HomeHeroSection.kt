package com.dongnemarket.mobile.ui.home.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dongnemarket.mobile.ui.theme.MarketOnTheme

/** 테스트가 홈 히어로의 동네 줄을 찾는 태그. */
const val TAG_HOME_REGION = "home_region"

/**
 * 홈 최상단 히어로. 브랜드 카피 + 내 동네 이름만 담는 크림색 카드다.
 *
 * ⚠️ **숫자 통계(거래 건수·회원 수·총 상품 수)를 넣지 않는다.**
 * 백엔드에 집계/통계 엔드포인트가 없고 상품 목록도 커서 페이징이라 전체 건수를 셀 방법조차 없다
 * (계약 §8-1). 하드코딩한 숫자는 데모에서 바로 거짓임이 드러나므로 아예 자리를 만들지 않았다.
 *
 * @param region 대표 동네 이름(`"서울 강남구"`). null 이면 **"동네를 설정해 주세요"** 로 바뀐다.
 *   예전에는 null 일 때 줄을 통째로 감췄다 — 동네 설정 화면이 없어 유도할 곳이 없었기 때문이다.
 *   이제 화면이 생겼으므로 감추는 대신 **설정으로 데려가는 입구**로 쓴다.
 *   동네가 없으면 홈이 전국 조회가 되고 상품 등록도 막히는데, 사용자는 그 이유를 알 길이 없었다.
 * @param onRegionClick 동네 줄을 눌렀다 — 동네 설정 화면으로 보내 달라.
 */
@Composable
fun HomeHeroSection(
    region: String?,
    onRegionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "우리 동네 중고거래",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "이웃이 내놓은 물건을 가까운 곳에서 만나 보세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .padding(top = 6.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onRegionClick)
                .testTag(TAG_HOME_REGION),
        ) {
            Icon(
                imageVector = Icons.Outlined.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = region ?: "동네를 설정해 주세요",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                // 누를 수 있다는 신호. 없으면 그냥 표시된 글자로 보인다.
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "동네 설정",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Preview(name = "히어로 (동네 있음)", showBackground = true)
@Composable
private fun HomeHeroSectionPreview() {
    MarketOnTheme {
        HomeHeroSection(region = "서울 강남구", onRegionClick = {}, modifier = Modifier.padding(16.dp))
    }
}

@Preview(name = "히어로 (동네 미설정)", showBackground = true)
@Composable
private fun HomeHeroSectionNoRegionPreview() {
    MarketOnTheme {
        HomeHeroSection(region = null, onRegionClick = {}, modifier = Modifier.padding(16.dp))
    }
}
