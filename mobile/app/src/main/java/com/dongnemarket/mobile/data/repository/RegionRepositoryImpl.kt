package com.dongnemarket.mobile.data.repository

import com.dongnemarket.mobile.data.mapper.toRegionDomain
import com.dongnemarket.mobile.data.remote.RegionApiService
import com.dongnemarket.mobile.data.remote.apiCall
import com.dongnemarket.mobile.domain.model.Region
import com.dongnemarket.mobile.domain.repository.RegionRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RegionRepository] 의 실제 구현. **단계별로 캐시한다.**
 *
 * ### 왜 캐시하는가
 * 드릴다운은 뒤로 가기를 반복하는 탐색이다 — 서울 → 종로구 → (뒤로) → 강남구처럼.
 * 캐시가 없으면 "서울의 시·군·구" 를 그때마다 다시 받는다.
 * 지역 마스터는 앱에서 바꿀 수 없고 서버에 관리 API 도 없으므로 **세션 동안 변하지 않는다.**
 *
 * ### 왜 리스트가 아니라 `Map` 인가
 * 예전에는 이 API 가 전량을 한 번에 준다고 보고 리스트 하나만 들고 있었다.
 * 지금은 `parentCode` 마다 결과가 다르므로 **키별로 따로** 보관해야 한다.
 * 키가 `String?` 인 것은 최상위(시·도) 조회가 `parentCode == null` 이기 때문이다.
 */
@Singleton
class RegionRepositoryImpl @Inject constructor(
    private val api: RegionApiService,
) : RegionRepository {

    /**
     * `parentCode` → 그 바로 아래 단계 목록.
     *
     * `@Volatile` 로 **참조 자체를 통째로 교체**한다(맵을 제자리에서 수정하지 않는다).
     * 그래서 읽기는 락 없이 안전하고, 쓰기만 [mutex] 안에서 한다.
     */
    @Volatile
    private var cache: Map<String?, List<Region>> = emptyMap()

    private val mutex = Mutex()

    override suspend fun getRegions(parentCode: String?): Result<List<Region>> {
        // 서버는 공백 문자열을 "파라미터 없음"과 같게 본다(StringUtils.hasText).
        // 캐시 키도 같은 기준으로 맞춰야 ""와 null 이 갈라져 같은 목록을 두 번 받지 않는다.
        val key = parentCode?.takeIf { it.isNotBlank() }

        cache[key]?.let { return Result.success(it) }

        return mutex.withLock {
            // 락을 기다리는 사이 앞선 호출이 채웠는지 다시 확인(중복 수신 방지).
            cache[key]?.let { return@withLock Result.success(it) }

            apiCall { api.getRegions(parentCode = key) }
                .map { it.toRegionDomain() }
                .onSuccess { regions -> cache = cache + (key to regions) }
        }
    }
}
