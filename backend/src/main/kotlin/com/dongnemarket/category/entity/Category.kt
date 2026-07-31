package com.dongnemarket.category.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 상품 분류 기준데이터. 이름만 가지며 생성 후 변경하지 않는다.
 *
 * `data class` 가 아니라 `class` — equals/hashCode 가 지연 로딩·JPA 동일성과 어긋난다.
 * JPA 어노테이션은 `@field:` 로 백킹 필드에 붙이고(필드 접근), id 는 `protected set`
 * (allOpen 이 프로퍼티도 open 으로 만들어 Kotlin 이 private setter 를 금지한다).
 *
 * `id` 가 `Long?` 인 이유: non-null 로 조이면 Java 에서 primitive `long` 이 되어
 * 아직 Java 인 호출부(`ProductControllerTest` 의 `getId().toString()`)가 깨진다.
 * 원본 시그니처를 그대로 옮기고, 전환 완료 후 별도 패스에서 조인다.
 *
 * 생성자를 private 으로 막고 팩토리를 두지 않은 이유: `CategorySeeder` 가 `Category::new` 를,
 * 테스트 16개가 `new Category(...)` 를 직접 쓴다. 팩토리를 도입하면 타 도메인 파일을
 * 수정해야 하므로 public 주 생성자를 유지한다.
 */
@Entity
@Table(name = "categories")
class Category(
    @field:Column(nullable = false, unique = true, length = 50)
    val name: String,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}
