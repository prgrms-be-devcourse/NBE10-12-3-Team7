package com.dongnemarket.category.repository

import com.dongnemarket.category.entity.Category
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles

/**
 * [통합] 카테고리 리포지토리의 파생 쿼리.
 *
 * `existsByName` 은 [com.dongnemarket.global.init.master.CategorySeeder] 의 유일한 소비자다.
 * 시더가 `filterNot { categoryRepository.existsByName(it) }` 로 중복 시딩을 막기 때문에,
 * 이 쿼리가 항상 false 를 돌려주면 **기동할 때마다 같은 카테고리가 계속 쌓인다.**
 * 앱은 정상 기동하고 에러도 안 나서 목록이 부풀기 전까지 아무도 모른다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CategoryRepositoryTest {
    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Test
    fun `카테고리 이름으로 존재 여부를 확인한다`() {
        categoryRepository.save(Category("디지털기기"))

        assertThat(categoryRepository.existsByName("디지털기기")).isTrue()
        assertThat(categoryRepository.existsByName("생활가전")).isFalse()
    }
}
