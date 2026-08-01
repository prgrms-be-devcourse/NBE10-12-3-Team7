package com.dongnemarket.region.dto

import com.dongnemarket.region.entity.Region

/**
 * 지역 응답. region 패키지 밖 사용처가 없어 `data class` 로 옮겨도 접근자 변화 영향이 없다.
 *
 * `regionId` 가 `Long?` 인 이유: [Region.id] 가 `Long?` 다(미영속 엔티티는 id 가 없다).
 * non-null 로 받으면 favorite 이 겪은 것과 같은 컴파일 충돌이 난다.
 *
 * `from` 에 `@JvmStatic` 이 필요한 이유: 전환 중인 지금 `RegionService` 는 아직 Java 이고
 * `RegionResponse::from` 메서드 참조를 쓴다. 없으면 Java 에서 심볼을 찾지 못한다.
 */
@ConsistentCopyVisibility
data class RegionResponse private constructor(
    val regionId: Long?,
    val code: String,
    val level: Int,
    // 최상위 지역(시·도)은 부모가 없어 null 이다.
    val parentCode: String?,
    val fullName: String,
    val displayName: String,
) {
    companion object {
        @JvmStatic
        fun from(region: Region): RegionResponse =
            RegionResponse(
                region.id,
                region.code,
                region.level,
                region.parent?.code,
                region.fullName,
                region.displayName,
            )
    }
}
