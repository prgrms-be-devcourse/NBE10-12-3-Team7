package com.dongnemarket.region.repository

import com.dongnemarket.region.entity.Region
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles

/**
 * [통합] 지역 리포지토리의 파생 쿼리 — 최상위 조회·부모 코드 기준 하위 조회·코드 존재 확인.
 * 정렬은 표시명(displayName) 오름차순이라 한글 정렬 순서가 실제 DB 에서 지켜지는지 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RegionRepositoryTest {
    @Autowired
    lateinit var regionRepository: RegionRepository

    @Test
    fun `최상위 지역을 표시명 오름차순으로 조회한다`() {
        val seoul = regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시"))
        val busan = regionRepository.save(Region.root("2600000000", "부산광역시", "부산광역시"))
        val gyeonggi = regionRepository.save(Region.root("4100000000", "경기도", "경기도"))

        assertThat(regionRepository.findAllByParentIsNullOrderByDisplayNameAsc())
            .containsExactly(gyeonggi, busan, seoul)
    }

    @Test
    fun `부모 코드 기준으로 직접 하위 지역을 표시명 오름차순 조회한다`() {
        val seoul = regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시"))
        val gangnam = regionRepository.save(Region.child("1168000000", 2, seoul, "서울특별시 강남구", "강남구"))
        val jongno = regionRepository.save(Region.child("1111000000", 2, seoul, "서울특별시 종로구", "종로구"))
        regionRepository.save(Region.root("2600000000", "부산광역시", "부산광역시"))

        assertThat(regionRepository.findAllByParentCodeOrderByDisplayNameAsc("1100000000"))
            .containsExactly(gangnam, jongno)
    }

    @Test
    fun `지역 코드를 기준으로 존재 여부를 확인한다`() {
        regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시"))

        assertThat(regionRepository.existsByCode("1100000000")).isTrue()
        assertThat(regionRepository.existsByCode("9999999999")).isFalse()
    }
}
