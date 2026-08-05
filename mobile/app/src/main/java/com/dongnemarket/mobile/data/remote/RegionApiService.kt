package com.dongnemarket.mobile.data.remote

import com.dongnemarket.mobile.data.remote.dto.ApiEnvelope
import com.dongnemarket.mobile.data.remote.dto.RegionResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 지역(동네) API 창구. 사용 규칙은 [CategoryApiService] 와 같다.
 */
interface RegionApiService {

    /**
     * `GET /api/regions` — 전국 지역 **229건 전량** 조회.
     *
     * 검색어·페이징 파라미터가 **없다**(계약 §8-10). 리포지토리에 `like` 조회 메서드조차 없어서
     * 서버에서 걸러 받는 방법이 아예 없다 → 한 번 받아 두고 **클라이언트가 메모리에서 필터링**한다.
     * 정렬은 `name` 가나다 ASC 고정.
     */
    /**
     * 지역 **한 단계**를 조회한다. 계층 전체를 한 번에 주는 API 는 없다.
     *
     * @param parentCode 이 지역의 **바로 아래** 단계를 받는다.
     *   - `null`(파라미터 생략) → 최상위 시·도 **16건**
     *   - 시·도 코드 → 그 안의 시·군·구
     *   - 시·군·구 코드 → 그 안의 읍·면·동
     *
     * 전량(5,338건)을 한 번에 받지 않는 이유는 서버가 그런 방식을 주지 않기 때문이다.
     * 읍면동만 5,067건이라 한 화면에 놓을 수도 없다 → **드릴다운이 유일한 탐색 방법**이다.
     *
     * 정렬은 서버가 `displayName ASC` 로 해 준다. 클라이언트에서 다시 정렬하지 않는다.
     */
    @GET("api/regions")
    suspend fun getRegions(
        @Query("parentCode") parentCode: String? = null,
    ): ApiEnvelope<List<RegionResponse>>
}
