package com.dongnemarket.category.service

import com.dongnemarket.category.dto.CategoryResponse
import com.dongnemarket.category.repository.CategoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CategoryService(
    private val categoryRepository: CategoryRepository,
) {
    fun getCategories(): List<CategoryResponse> = categoryRepository.findAllByOrderByIdAsc().map(CategoryResponse::from)
}
