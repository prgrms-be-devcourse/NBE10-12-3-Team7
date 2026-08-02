package com.dongnemarket.mobile.data.mapper

import com.dongnemarket.mobile.data.remote.dto.CategoryResponse
import com.dongnemarket.mobile.data.remote.dto.RegionResponse
import com.dongnemarket.mobile.domain.model.Category
import com.dongnemarket.mobile.domain.model.Region

/**
 * DTO(서버 계약) → 도메인 모델 변환. 카테고리·지역 담당.
 *
 * 이 경계가 왜 필요한가: DTO 는 **서버 JSON 모양에 종속된 타입**이다.
 * 카테고리 PK 키가 `id`, 지역 PK 키가 `regionId` 로 비대칭인 것도 서버 사정일 뿐이라
 * 그대로 UI 까지 흘리면 화면 코드가 백엔드 명명 실수까지 따라 하게 된다.
 * 여기서 한 번 번역해 두면 서버 키 이름이 바뀌어도 고칠 곳이 이 파일 한 곳이다.
 *
 * (JPA Entity → Response DTO 로 변환하던 Spring 쪽 매퍼와 방향만 반대인 같은 자리다.)
 */

/** 카테고리 DTO 한 건 → 도메인. 서버 키 `id` 를 도메인 [Category.id] 로 옮긴다. */
fun CategoryResponse.toDomain(): Category = Category(
    id = id,
    name = name,
)

/** 카테고리 목록 변환. **서버가 준 순서(id ASC = 시드 순서)를 유지한다** — 재정렬 금지(계약 §8-8). */
fun List<CategoryResponse>.toCategoryDomain(): List<Category> = map { it.toDomain() }

/**
 * 지역 DTO 한 건 → 도메인. 서버 키는 `regionId` 다(카테고리는 `id` — 비대칭 유지).
 *
 * ⚠️ 2026-07 개편으로 필드가 `{regionId, name}` → 6개로 교체됐다.
 * `regionId` 는 서버가 null 을 줄 수 있어(`Long?`) 목록 key 로 쓰려면 채워야 하는데,
 * 지역은 `code` 가 사실상 고유하므로 **id 가 없으면 code 의 해시로 대체**한다.
 * (LazyColumn 의 key 는 안정적이기만 하면 되고 서버 PK 일 필요는 없다.)
 */
fun RegionResponse.toDomain(): Region = Region(
    regionId = regionId ?: code.hashCode().toLong(),
    code = code,
    level = level,
    parentCode = parentCode,
    fullName = fullName,
    displayName = displayName,
)

/**
 * 지역 목록 변환. 서버 순서를 유지한다(재정렬 금지).
 *
 * 문자열을 `trim()` 하거나 정규화하지 **않는다.** 예전에는 이름 문자열이 그대로 요청에 실려
 * 서버에서 완전 비교됐기 때문인데, 지금은 [Region.code] 로 통신하므로 그 위험은 사라졌다.
 * 그래도 표시 문자열을 임의로 손대지 않는 원칙은 유지한다 — 서버가 준 그대로가 정본이다.
 */
fun List<RegionResponse>.toRegionDomain(): List<Region> = map { it.toDomain() }
