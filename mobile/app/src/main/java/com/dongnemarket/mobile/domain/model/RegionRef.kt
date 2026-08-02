package com.dongnemarket.mobile.domain.model

/**
 * 다른 응답(상품·찜·채팅·내 동네) 안에 **박혀서 오는 지역 참조**.
 *
 * ### 왜 값 객체로 묶었나
 * 백엔드가 2026-07 지역 모델을 개편하면서 응답의 `region: String` 하나가
 * `regionCode`·`regionName`·`regionFullName` **세 개로 쪼개졌다.**
 * 이 셋을 [Product]·[ProductDetail]·[MemberLocation] 마다 각각 늘어놓으면 필드가 3배로 불고,
 * "표시에는 무엇을 쓰고 통신에는 무엇을 쓰나"라는 규칙이 모델마다 흩어진다.
 * 한 덩어리로 묶어 두면 화면은 `product.region.name` 한 줄이면 되고, 규칙은 여기 한 곳에만 있다.
 *
 * ### [Region] 과 무엇이 다른가
 * | | 무엇 | 어디서 오나 |
 * | --- | --- | --- |
 * | [Region] | 지역 **마스터** 목록의 한 건(계층 정보 포함) | `GET /api/regions` |
 * | [RegionRef] | 다른 응답에 **박혀 오는 참조** | 상품·찜·채팅·내 동네 응답 |
 *
 * @property code 서버와 통신할 때 쓰는 값. 상품 필터(`?regionCodes=`)·내 동네 설정이 이걸 받는다.
 *   **이름 문자열을 보내던 옛 방식은 더 이상 통하지 않는다.**
 * @property name 짧은 표시 이름(`"강남구"`). 카드처럼 폭이 좁은 자리에 쓴다.
 * @property fullName 전체 표시 이름(`"서울특별시 강남구"`). 상세처럼 여유가 있는 자리에 쓴다.
 */
data class RegionRef(
    val code: String,
    val name: String,
    val fullName: String,
) {
    /**
     * 화면에 보여 줄 기본 문구. 짧은 이름이 비어 있으면 전체 이름으로 떨어진다.
     *
     * 서버가 셋 중 일부를 비워 보내는 경우(옛 데이터·마이그레이션 잔재)에도
     * 화면에 빈칸이 뜨지 않도록 하는 방어다.
     */
    val display: String
        get() = name.ifBlank { fullName }

    companion object {
        /** 서버가 지역 정보를 아예 주지 않았을 때 쓰는 빈 값. 화면은 빈 문자열을 그린다. */
        val EMPTY = RegionRef(code = "", name = "", fullName = "")
    }
}
