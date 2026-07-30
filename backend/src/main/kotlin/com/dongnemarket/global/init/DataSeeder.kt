package com.dongnemarket.global.init

/**
 * 모든 시더의 공통 계약(Strategy). SeedOrchestrator 가 order 오름차순으로 실행한다.
 * 구현체의 seed()는 멱등해야 하며(여러 번 호출해도 중복 생성 없음),
 * 프록시 `@Transactional` 이 걸리도록 반드시 public 이어야 한다
 * (Kotlin 은 기본 가시성이 public 이라 키워드를 쓰지 않는다).
 */
interface DataSeeder {
    /** 실행 순서. 작을수록 먼저. (마스터 10~11, 부트스트랩 20, 데모 30) */
    fun order(): Int

    /** 데이터 시딩 동작(멱등). */
    fun seed()
}
