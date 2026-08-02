package com.dongnemarket.mobile.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * `GET /api/categories` 응답 `data` 배열의 원소.
 * 원본: backend `com.dongnemarket.category.dto.CategoryResponse`
 *
 * ```json
 * { "id": 1, "name": "디지털기기" }
 * ```
 *
 * ⚠ **PK 키 이름이 `id` 다.** 지역은 `regionId` 인데 카테고리는 `id` 로 비대칭이다.
 * `categoryId` 로 선언하면 예외 없이 조용히 파싱 실패(값 없음)로 이어진다.
 *
 * 필드는 이 두 개가 전부다. `iconUrl`/`sortOrder`/`parentId` 는 서버에 존재하지 않는다.
 */
@Serializable
data class CategoryResponse(
    val id: Long,
    val name: String,
)

/**
 * `GET /api/regions` 응답 `data` 배열의 원소.
 * 원본: backend `region/dto/RegionResponse.kt`
 *
 * ```json
 * { "regionId": 1, "code": "11680", "level": 2, "parentCode": "11",
 *   "fullName": "서울특별시 강남구", "displayName": "강남구" }
 * ```
 *
 * ### ⚠️ 2026-07 전면 교체된 DTO다
 * 이전에는 `{ regionId, name }` 두 개뿐이었고 **이름 문자열로 통신**했다.
 * 지금은 **[code] 로 통신**하고, [level]·[parentCode] 로 **계층 탐색**까지 된다.
 * 옛 `name` 키는 서버에 존재하지 않으므로 그대로 두면 파싱이 실패한다.
 *
 * ⚠ 카테고리는 PK 키가 `id`, 지역은 `regionId` — 비대칭은 그대로다.
 *
 * @property code 상품 필터·내 동네 설정에 넣는 값
 * @property level 계층 깊이(시도 → 시군구 …)
 * @property parentCode 상위 지역 코드. 최상위는 null
 * @property fullName `"서울특별시 강남구"`
 * @property displayName `"강남구"` — 목록에 보여 줄 짧은 이름
 */
@Serializable
data class RegionResponse(
    val regionId: Long? = null,
    val code: String = "",
    val level: Int = 0,
    val parentCode: String? = null,
    val fullName: String = "",
    val displayName: String = "",
)
