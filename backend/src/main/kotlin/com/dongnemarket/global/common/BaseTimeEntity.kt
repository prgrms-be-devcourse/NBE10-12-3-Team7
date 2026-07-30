package com.dongnemarket.global.common

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * 생성/수정 시각 공통 매핑.
 *
 * 각 도메인 Entity 가 상속해 createdAt/updatedAt 을 자동 관리한다.
 * (소프트 삭제용 deleted_at 은 도메인 요구사항이 다르므로 각 Entity 에서 정의한다.)
 *
 * `abstract class` 라서 Kotlin 기본 final 규칙에 걸리지 않는다 —
 * 아직 Java 인 엔티티 18개가 그대로 `extends BaseTimeEntity` 할 수 있다.
 *
 * 어노테이션에 `@field:` 를 명시하는 이유: JPA 는 이 계층에서 필드 접근(field access)을 쓰므로
 * 어노테이션이 필드에 붙어야 `updatable = false` 같은 설정이 유효하다.
 *
 * setter 가 `private` 이 아니라 `protected` 인 이유: build.gradle 의 `allOpen` 이
 * `@MappedSuperclass`/`@Entity` 를 open 으로 만들면서 **프로퍼티도 open** 이 된다.
 * Kotlin 은 open 프로퍼티에 private setter 를 금지하므로(컴파일 에러) protected 를 쓴다.
 * 엔티티를 전환하는 모든 담당자에게 동일하게 적용되는 제약이다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseTimeEntity {
    @field:CreatedDate
    @field:Column(updatable = false)
    var createdAt: LocalDateTime? = null
        protected set

    @field:LastModifiedDate
    var updatedAt: LocalDateTime? = null
        protected set
}
