package com.dongnemarket.global.init.master

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.global.init.DataSeeder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 기준데이터: 기본 카테고리 8종. 모든 환경에서 항상 실행(멱등).
 *
 * global 2차 중 이 시더만 먼저 전환할 수 있었다 — `Category` 가 이미 Kotlin 이라
 * 플랫폼 타입을 거치지 않는다. 나머지 시더는 member/product/region/report 엔티티를 기다린다.
 */
@Component
class CategorySeeder(
    private val categoryRepository: CategoryRepository,
) : DataSeeder {
    override fun order(): Int = 10

    @Transactional
    override fun seed() {
        DEFAULT_CATEGORY_NAMES
            .filterNot { categoryRepository.existsByName(it) }
            .map(::Category)
            .forEach(categoryRepository::save)
    }

    companion object {
        private val DEFAULT_CATEGORY_NAMES =
            listOf(
                "디지털기기",
                "생활가전",
                "가구/인테리어",
                "의류",
                "도서",
                "스포츠/레저",
                "반려동물용품",
                "기타",
            )
    }
}
