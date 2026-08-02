package com.dongnemarket.mobile.data.mapper

import com.dongnemarket.mobile.data.remote.dto.ProductPageResponse
import com.dongnemarket.mobile.data.remote.dto.ProductResponse
import com.dongnemarket.mobile.data.remote.dto.ProductSummaryResponse
import com.dongnemarket.mobile.domain.model.Product
import com.dongnemarket.mobile.domain.model.ProductDetail
import com.dongnemarket.mobile.domain.model.ProductPage
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.domain.model.TradeStatus

/**
 * 상품 DTO → 도메인 모델 변환. Data 계층의 경계선이다.
 *
 * 여기서 하는 일 3가지:
 * 1. 서버 키 이름을 앱 어휘로 바꾼다(`memberId` → `sellerId`).
 * 2. 문자열 상태를 [TradeStatus] 로 안전 변환한다(모르는 값 → UNKNOWN).
 * 3. 상대 경로 이미지를 절대 URL 로 바꾼다([toAbsoluteImageUrl]).
 *
 * 이 변환을 거치므로 UI·ViewModel 은 DTO 를 몰라도 되고, 서버 응답이 바뀌어도
 * 고칠 곳이 이 파일 하나로 좁혀진다.
 */

/** 목록/검색 응답 1건 → 카드 모델. */
fun ProductSummaryResponse.toDomain(): Product = Product(
    productId = productId,
    sellerId = memberId,
    categoryId = categoryId,
    title = title,
    price = price,
    tradeStatus = TradeStatus.from(tradeStatus),
    region = toRegionRef(regionCode, regionName, regionFullName),
    viewCount = viewCount,
    favoriteCount = favoriteCount,
    thumbnailUrl = thumbnailUrl.toAbsoluteImageUrl(),
    hidden = hidden,
)

/**
 * 서버가 흩어 보내는 지역 3필드를 한 덩어리로 묶는다.
 *
 * 셋 다 계약상 항상 오지만 null 을 빈 문자열로 흡수한다 — 지역 하나 때문에 목록 전체가
 * 죽는 것보다 그 칸만 비는 편이 낫다. 전부 비면 [RegionRef.EMPTY] 와 같은 값이 된다.
 */
private fun toRegionRef(
    code: String?,
    name: String?,
    fullName: String?,
): RegionRef = RegionRef(
    code = code.orEmpty(),
    name = name.orEmpty(),
    fullName = fullName.orEmpty(),
)

/** 커서 페이징 래퍼 → 도메인 페이지. */
fun ProductPageResponse.toDomain(): ProductPage = ProductPage(
    items = items.map { it.toDomain() },
    nextCursor = nextCursor,
    hasNext = hasNext,
)

/** 상세 응답 → 상세 모델. */
fun ProductResponse.toDomain(): ProductDetail = ProductDetail(
    productId = productId,
    sellerId = memberId,
    // 탈퇴 회원이면 서버가 "탈퇴한 사용자" 를 넣어 준다. 그래도 null 방어는 해 둔다.
    sellerNickname = sellerNickname.orEmpty(),
    categoryId = categoryId,
    title = title,
    description = description.orEmpty(),
    price = price,
    tradeStatus = TradeStatus.from(tradeStatus),
    region = toRegionRef(regionCode, regionName, regionFullName),
    viewCount = viewCount,
    favoriteCount = favoriteCount,
    thumbnailUrl = thumbnailUrl.toAbsoluteImageUrl(),
    imageUrls = imageUrls.toAbsoluteImageUrls(),
    hidden = hidden,
)
