package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.EmailVerification
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * 전환 규칙 — 반환 타입 `Optional` 을 Kotlin nullable 로 바꾸지 않는다. Java 호출부
 * (`AuthService`·`EmailVerificationService`)가 `.orElseThrow(...)` 등 `Optional` API 를 그대로 쓴다.
 *
 * 파라미터가 nullable 인 이유 — 원본 Java 가 참조형 `String` 이다. non-null 로 조이면 JVM descriptor 는
 * 같지만 Kotlin 호출부가 `!!` 를 강제받아 실패 위치가 앞당겨진다(5단계에서 실제로 발생했다).
 *
 * [existsByEmailAndVerifiedTrue] 는 Spring Data 파생 쿼리라 엔티티 속성명 `verified` 에 그대로 묶여 있다
 * ([EmailVerification] 의 프로퍼티 이름을 바꾸면 기동 시점에 깨진다).
 */
interface EmailVerificationRepository : JpaRepository<EmailVerification, Long> {
    fun findByEmail(email: String?): Optional<EmailVerification>

    /** 회원가입 시 해당 이메일이 인증 완료 상태인지 확인할 때 사용 */
    fun existsByEmailAndVerifiedTrue(email: String?): Boolean
}
