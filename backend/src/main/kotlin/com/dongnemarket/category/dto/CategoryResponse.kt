package com.dongnemarket.category.dto

import com.dongnemarket.category.entity.Category

/**
 * 카테고리 응답. `record` → `data class` 로 옮기면서 접근자가 `id()` → `getId()` 로 바뀌지만
 * 이 타입은 category 패키지 밖에서 쓰이지 않아 깨질 호출부가 없다.
 *
 * `from` 에 `@JvmStatic` 이 필요한 이유: 전환이 진행 중인 지금 `CategoryService` 는 아직
 * Java 이고 `CategoryResponse::from` 메서드 참조를 쓴다. 없으면 Java 에서
 * `CategoryResponse.Companion::from` 이 되어 컴파일이 깨진다.
 */
data class CategoryResponse(
    val id: Long?,
    val name: String,
) {
    companion object {
        @JvmStatic
        fun from(category: Category): CategoryResponse = CategoryResponse(category.id, category.name)
    }
}
