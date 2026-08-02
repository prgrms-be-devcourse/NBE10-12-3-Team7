package com.dongnemarket.mobile.domain.model

import java.math.BigDecimal

/**
 * 목록 카드 1장에 필요한 상품 요약. (백엔드 `ProductSummaryResponse` 대응)
 *
 * 홈 목록 / 검색 / 카테고리 필터 세 화면이 모두 이 모델을 쓴다.
 *
 * 주의 — **작성 시각 필드가 없다.** 백엔드 상품 응답에 `createdAt`/`updatedAt` 이
 * 아예 없어서 "3시간 전" 표기를 만들 방법이 없다. 카드의 그 자리에는 [region] 을 쓴다.
 * (목록 정렬이 항상 id DESC 이므로 "최신순"이라는 레이블 자체는 붙일 수 있다.)
 *
 * @property sellerId 판매자 회원 PK(서버 키 이름은 `memberId`). 요약에는 판매자 닉네임이 없다.
 * @property price BigDecimal 이다. 서버가 `800000.00` 처럼 소수부를 붙여 주므로 Int/Long 으로 받으면 깨진다.
 * @property region 지역 참조([RegionRef]). 카드에는 `region.display`(짧은 이름)를 쓰고,
 *   필터로 서버에 보낼 때는 `region.code` 를 쓴다. 2026-07 개편 전에는 이름 문자열 하나였다.
 * @property thumbnailUrl 이미 절대 URL 로 변환된 값. 이미지가 없는 상품은 null 이므로 화면에서 플레이스홀더를 그린다.
 * @property hidden 숨김 여부. 목록/검색 응답에서는 서버가 숨김 상품을 걸러 주므로 항상 false 다.
 */
data class Product(
    val productId: Long,
    val sellerId: Long,
    val categoryId: Long,
    val title: String,
    val price: BigDecimal,
    val tradeStatus: TradeStatus,
    val region: RegionRef,
    val viewCount: Long,
    val favoriteCount: Int,
    val thumbnailUrl: String?,
    val hidden: Boolean,
) {
    /**
     * '나눔' 판정. 서버에는 나눔 상태가 없고 **가격 0원이 나눔을 뜻한다**는 것이 앱 측 규약이다.
     * 배지를 '판매중'으로 그릴지 '나눔'으로 그릴지는 UI 가 이 값으로 결정한다.
     */
    val isGiveaway: Boolean
        get() = tradeStatus == TradeStatus.ON_SALE && price.signum() == 0
}
