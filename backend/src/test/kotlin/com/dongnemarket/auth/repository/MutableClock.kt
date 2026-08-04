package com.dongnemarket.auth.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 테스트 전용 Clock. 시간은 오직 [advance] 호출로만 흐른다 — 시스템 시계와 완전히 분리돼
 * 시계 정밀도·스케줄링 지연에 영향받지 않는 결정적 시간 검증을 가능하게 한다.
 *
 * 인스턴스 단위 상태만 가지며 전역 상태를 공유하지 않으므로, 테스트마다 새로 생성해 쓴다.
 */
class MutableClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    fun advance(amount: Duration) {
        current += amount
    }

    override fun instant(): Instant = current

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
}
