package com.dongnemarket.mobile.domain.model

/**
 * 지역 **마스터** 목록의 한 건. `GET /api/regions` 가 주는 항목이다.
 *
 * ### ⚠️ 통신에 쓰는 값은 [code] 다 ([regionId] 도, 이름도 아니다)
 * 2026-07 백엔드 지역 모델 개편 이후:
 *  - 상품 목록/검색 필터: `?regionCodes=11680&regionCodes=11440` ← [code], **최대 2개**
 *  - 내 동네 설정(`PUT /api/members/me/locations`): `{"regionCodes":["11680"]}` ← [code]
 *
 * 옛 방식(이름 문자열 전송)은 더 이상 통하지 않는다. 이름을 보내면 서버가 코드로 못 찾아 400 이거나
 * 조용히 0건이 된다.
 *
 * ### 계층 구조가 생겼다
 * [level] 과 [parentCode] 로 시도 → 시군구를 타고 내려갈 수 있다.
 * `GET /api/regions?parentCode={code}` 로 하위 목록만 받아오면 되므로,
 * 예전처럼 전체를 한 번에 받아 클라이언트가 문자열을 잘라 그룹핑할 필요가 없다.
 *
 * ### [RegionRef] 와의 차이
 * 이 클래스는 **선택 화면에 뿌릴 마스터 데이터**다.
 * 상품·채팅·내 동네 응답에 박혀 오는 지역 정보는 [RegionRef] 를 쓴다.
 *
 * @property regionId 지역 PK. 통신에 쓰지 않고 리스트 key(`key = { it.regionId }`) 용도다.
 * @property code 서버와 통신하는 값
 * @property level 계층 깊이
 * @property parentCode 상위 지역 코드. 최상위(시도)는 null
 * @property fullName `"서울특별시 강남구"`
 * @property displayName `"강남구"` — 목록에 보여 줄 짧은 이름
 */
data class Region(
    val regionId: Long,
    val code: String,
    val level: Int,
    val parentCode: String?,
    val fullName: String,
    val displayName: String,
) {

    /** 최상위(시도) 항목인가. 하위를 더 파고들 수 있는지 판단할 때 쓴다. */
    val isTopLevel: Boolean
        get() = parentCode == null

    /** 다른 응답에 박혀 오는 형태로 변환. 내 동네를 고른 직후처럼 즉시 표시해야 할 때 쓴다. */
    fun toRef(): RegionRef = RegionRef(code = code, name = displayName, fullName = fullName)
}
