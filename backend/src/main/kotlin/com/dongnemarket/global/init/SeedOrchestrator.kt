package com.dongnemarket.global.init

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 모든 DataSeeder 를 order 오름차순으로 실행하는 단일 진입점.
 * ApplicationReadyEvent(모든 빈 초기화 완료 후) 시점에 1회 실행한다.
 *
 * 스프링이 활성 프로파일/조건에 맞는 DataSeeder 빈만 주입하므로,
 * 여기에는 실행 여부를 가르는 조건 분기가 없다(필터링은 빈 등록 시점에 끝난다).
 *
 * run() 자체에는 `@Transactional` 을 붙이지 않는다. 각 seed()가 개별 트랜잭션이라
 * 앞 시더가 '커밋된 뒤' 다음 시더가 실행된다 → 마스터(카테고리) 커밋 후 데모가 그것을 조회.
 */
@Component
class SeedOrchestrator(
    seeders: List<DataSeeder>,
) {
    /**
     * 생성자 파라미터 `seeders` 를 정렬해 프로퍼티로 굳힌다.
     * Java 의 `Comparator.comparingInt(DataSeeder::order)` 가 `sortedBy { it.order() }` 로 줄어든다.
     */
    private val seeders: List<DataSeeder> = seeders.sortedBy { it.order() }

    @EventListener(ApplicationReadyEvent::class)
    fun run() {
        for (seeder in seeders) {
            log.info("[Seed] {} (order={})", seeder.javaClass.simpleName, seeder.order())
            seeder.seed()
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(SeedOrchestrator::class.java)
    }
}
