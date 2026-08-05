package com.dongnemarket.mobile.domain.repository

import com.dongnemarket.mobile.domain.model.Region

/**
 * 지역(동네) 조회 창구. 설계 원칙은 [CategoryRepository] 와 같다.
 *
 * ### 범위 주의
 * 이 Repository 는 **"전국에 어떤 동네가 있는가"(마스터 데이터)만** 다룬다.
 * "내 동네가 무엇인가"(`GET·PUT /api/members/me/locations`)는 회원 도메인의 책임이며
 * `MemberRepository` 가 제공한다 → 여기에 중복 구현하지 마라.
 */
interface RegionRepository {

    /**
     * 지역 **한 단계**를 조회한다(`GET /api/regions?parentCode=`).
     *
     * 서버가 계층 전체를 주는 API 를 두지 않았다. 그래서 한 단계씩 내려가며 받는다.
     *
     * ```
     * getRegions(null)   → 시·도 16건
     * getRegions("11")   → 서울특별시 안의 시·군·구
     * getRegions("1111") → 종로구 안의 읍·면·동
     * ```
     *
     * ### 왜 전량을 받지 않는가
     * 전국이 **시·도 16 + 시·군·구 255 + 읍·면·동 5,067 = 5,338건**이다.
     * 검색 API 도 페이징도 없어서 한 번에 받으면 5천 건을 메모리에 얹은 채
     * 사용자가 스크롤로 찾아야 한다 → **드릴다운이 서버가 준 유일한 탐색 방법이다.**
     *
     * ⚠️ **내 동네·상품 등록에 쓸 수 있는 것은 읍·면·동([Region.level] == 3)뿐이다.**
     * 그 위 단계 코드를 보내면 서버가 400 `INVALID_INPUT_VALUE` 를 준다.
     *
     * @param parentCode 이 지역의 바로 아래 단계를 받는다. null 이면 최상위(시·도).
     *
     * 결과는 서버가 `displayName ASC` 로 정렬해 준다 — **재정렬하지 말고 그대로 그린다.**
     * 구현체가 단계별로 캐시하므로 뒤로 갔다 다시 들어와도 같은 요청이 반복되지 않는다.
     */
    suspend fun getRegions(parentCode: String? = null): Result<List<Region>>
}
