package com.dongnemarket.global.init.master

import com.dongnemarket.category.repository.CategoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class CategorySeederTest {
    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var categorySeeder: CategorySeeder

    @Test
    fun `애플리케이션 시작 시 기본 카테고리 8개가 저장된다`() {
        categorySeeder.seed()

        val categoryNames = categoryRepository.findAll().map { it.name }

        assertThat(categoryNames).containsExactlyInAnyOrderElementsOf(DEFAULT_CATEGORY_NAMES)
    }

    @Test
    fun `시더를 다시 실행해도 기본 카테고리가 중복 저장되지 않는다`() {
        categorySeeder.seed()

        assertThat(categoryRepository.count()).isEqualTo(DEFAULT_CATEGORY_NAMES.size.toLong())
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
