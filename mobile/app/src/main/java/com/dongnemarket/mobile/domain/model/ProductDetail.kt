package com.dongnemarket.mobile.domain.model

import java.math.BigDecimal

/**
 * 상품 상세 화면 1개 분량의 데이터. (백엔드 `ProductResponse` 대응)
 *
 * [Product] 의 상위집합이지만 별도 모델로 두는 이유:
 * 요약 응답에는 [description]·[sellerNickname]·[imageUrls] 가 없어서
 * 한 모델로 합치면 목록에서 쓸 수 없는 빈 필드가 잔뜩 생긴다.
 *
 * 여기에도 **작성 시각이 없다**(서버 미노출). 상세 상단에도 시간 대신 [region] 을 쓴다.
 *
 * @property sellerId 판매자 회원 PK(서버 키 이름은 `memberId`). 내 상품인지 판정해 '채팅하기' 버튼을 숨길 때 쓴다.
 * @property viewCount 이 조회로 +1 된 값이다(GET 이 조회수를 올린다).
 * @property favoriteCount 찜 개수. **'내가 찜했는지'는 이 응답에 없다** → 찜 도메인이 따로 판정한다.
 * @property imageUrls 절대 URL 로 변환된 이미지 목록. sortOrder ASC, 최대 5개, 이미지 없으면 빈 리스트다.
 */
data class ProductDetail(
    val productId: Long,
    val sellerId: Long,
    val sellerNickname: String,
    val categoryId: Long,
    val title: String,
    val description: String,
    val price: BigDecimal,
    val tradeStatus: TradeStatus,
    /** 지역 참조. 상세는 여유가 있으므로 `region.fullName`(전체 이름)을 써도 좋다. */
    val region: RegionRef,
    val viewCount: Long,
    val favoriteCount: Int,
    val thumbnailUrl: String?,
    val imageUrls: List<String>,
    val hidden: Boolean,
) {
    /** '나눔' 판정. 근거는 [Product.isGiveaway] 와 같다(가격 0원 = 나눔, 앱 측 규약). */
    val isGiveaway: Boolean
        get() = tradeStatus == TradeStatus.ON_SALE && price.signum() == 0
}
