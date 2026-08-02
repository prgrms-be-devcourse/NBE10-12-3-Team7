package com.dongnemarket.mobile.data.repository

import com.dongnemarket.mobile.data.mapper.toDomain
import com.dongnemarket.mobile.data.remote.ProductApiService
import com.dongnemarket.mobile.data.remote.apiCall
import com.dongnemarket.mobile.domain.model.Product
import com.dongnemarket.mobile.domain.model.ProductDetail
import com.dongnemarket.mobile.domain.model.ProductPage
import com.dongnemarket.mobile.domain.repository.ProductRepository
import javax.inject.Inject

/**
 * [ProductRepository] 의 실제 구현. HTTP 호출 → 껍데기 벗기기 → 도메인 변환까지 담당한다.
 *
 * 패턴은 세 함수 모두 같다: `apiCall { api.xxx() }.map { it.toDomain() }`
 *  - `apiCall` 이 예외를 [com.dongnemarket.mobile.domain.model.AppError] 로 번역해 `Result` 에 담아 준다
 *    → 이 클래스는 try/catch 를 쓰지 않고, 예외를 밖으로 던지지도 않는다.
 *  - `Result.map` 은 성공일 때만 실행되므로 실패는 그대로 통과한다.
 */
// 싱글톤 스코프는 ProductRepositoryModule 의 @Binds @Singleton 에서 한 번만 지정한다.
class ProductRepositoryImpl @Inject constructor(
    private val api: ProductApiService,
) : ProductRepository {

    override suspend fun getProducts(
        regionCodes: List<String>?,
        cursor: Long?,
        size: Int,
    ): Result<ProductPage> =
        apiCall {
            api.getProducts(
                regionCodes = regionCodes.normalizeRegionCodes(),
                cursor = cursor,
                size = size.normalizePageSize(),
            )
        }.map { it.toDomain() }

    override suspend fun searchProducts(
        keyword: String?,
        categoryId: Long?,
        regionCodes: List<String>?,
    ): Result<List<Product>> =
        apiCall {
            api.searchProducts(
                // 빈 문자열을 그대로 보내면 "제목에 ''를 포함"이라는 무의미한 조건이 붙는다 → 아예 뺀다.
                keyword = keyword?.trim()?.takeIf { it.isNotEmpty() },
                categoryId = categoryId,
                regionCodes = regionCodes.normalizeRegionCodes(),
            )
        }.map { list -> list.map { it.toDomain() } }

    /**
     * ⚠️ 서버에서 조회수를 올리는 호출이다. 재시도·재구성으로 중복 호출되지 않게
     * 호출자(ViewModel)가 1회 로드를 보장해야 한다. 자세한 주의사항은 인터페이스 KDoc 참고.
     */
    override suspend fun getProductDetail(productId: Long): Result<ProductDetail> =
        apiCall { api.getProductDetail(productId) }.map { it.toDomain() }

    private companion object {
        /** 서버 지역 필터 상한. 3개 이상 보내면 400 이 떨어진다. */
        const val MAX_REGION_FILTER = 2

        /** 서버 기본 페이지 크기. size 가 0 이하일 때 서버가 쓰는 값과 같아야 한다(계약 §0.9/§7-19). */
        const val DEFAULT_PAGE_SIZE = 30
        const val MAX_PAGE_SIZE = 100
    }

    /**
     * 페이지 크기 방어 처리. 상한은 서버와 같이 100 으로 깎는다.
     *
     * 하한을 1 이 아니라 [DEFAULT_PAGE_SIZE] 로 되돌리는 이유:
     * 서버는 `size <= 0` 을 기본값 30 으로 해석한다(계약 §0.9/§7-19).
     * 여기서 1 로 클램프하면 서버라면 30건을 줬을 상황에 페이지당 1건짜리 무한스크롤이 되어
     * 에러도 로그도 없이 성능만 무너진다.
     */
    private fun Int.normalizePageSize(): Int =
        if (this <= 0) DEFAULT_PAGE_SIZE else coerceAtMost(MAX_PAGE_SIZE)

    /**
     * 지역 필터 방어 처리. 서버가 400/500 을 내기 전에 클라이언트에서 정리한다:
     * 공백 원소 제거 → 중복 제거 → **앞의 2개만** 사용 → 남은 게 없으면 null(파라미터 생략).
     *
     * 조용히 잘라내는 편을 택한 이유: 지역명은 사용자가 직접 타이핑하는 값이 아니라
     * '내 동네'(서버가 최대 2개만 저장) 에서 온 값이므로 3개가 들어오는 건 앱 버그이고,
     * 그때 화면 전체를 에러로 덮는 것보다 1·2번 동네 결과를 보여 주는 편이 낫다.
     *
     * ⚠️ **미해결 계약 충돌 — 손대기 전에 팀 합의가 필요하다.**
     * 여기서는 원소를 `trim()` 하지만, `CatalogMapper.toRegionDomain()` 은
     * "서버가 이름 문자열을 완전 비교하므로 한 글자도 손대지 않는다(계약 §7-18)" 고 명시하고 그렇게 구현돼 있다.
     * 즉 매퍼가 일부러 보존한 원문을 이 함수가 다시 다듬는다.
     * 지역 마스터 데이터에 앞뒤 공백이 포함된 이름이 하나라도 있으면
     * 400 도 예외도 없이 조용히 '결과 0건'(빈 홈 화면)이 된다.
     * 현재 두 테스트가 이 모순을 각각 반대 방향으로 고정하고 있다
     * (ProductApiContractTest `공백과 중복이 섞인 지역 목록은 정리되어 하나만 나간다` ↔
     *  CatalogRepositoryImplTest `지역 이름은 공백까지 서버 원문 그대로 보존된다`).
     */
    private fun List<String>?.normalizeRegionCodes(): List<String>? =
        this?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.take(MAX_REGION_FILTER)
            ?.takeIf { it.isNotEmpty() }
}
