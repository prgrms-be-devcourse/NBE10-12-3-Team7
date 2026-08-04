package com.dongnemarket.global.init.master

import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
class RegionSeederTest {
    @Autowired
    lateinit var regionRepository: RegionRepository

    @Autowired
    lateinit var regionSeeder: RegionSeeder

    @Test
    fun `애플리케이션 시작 시 계층형 전국 지역 마스터가 저장된다`() {
        val regionNames = regionRepository.findAll().map { it.fullName }

        assertThat(regionNames).isNotEmpty()
        assertThat(regionNames).containsAll(REPRESENTATIVE_REGION_NAMES)
        assertThat(regionRepository.countByLevel(1)).isEqualTo(16L)
        assertThat(regionRepository.countByLevel(2)).isEqualTo(255L)
        assertThat(regionRepository.countByLevel(3)).isEqualTo(5067L)
    }

    @Test
    fun `시더를 다시 실행해도 지역이 중복 저장되지 않는다`() {
        val beforeCount = regionRepository.count()

        regionSeeder.seed()

        assertThat(regionRepository.count()).isEqualTo(beforeCount)
    }

    @Test
    @Transactional
    fun `일부 지역만 저장되어 있으면 누락된 지역만 보충한다`() {
        regionRepository.deleteByLevel(3)
        regionRepository.deleteByLevel(2)
        regionRepository.deleteByLevel(1)
        regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시"))

        regionSeeder.seed()

        val regionNames = regionRepository.findAll().map { it.fullName }
        assertThat(regionNames).containsAll(REPRESENTATIVE_REGION_NAMES)
        assertThat(regionNames).filteredOn { it == "서울특별시" }.hasSize(1)
        assertThat(regionNames).filteredOn { it == "서울특별시 강남구" }.hasSize(1)
    }

    @Test
    fun `세종은 시도 바로 아래에 읍면동이 연결된다`() {
        val children = regionRepository.findAllByParentCodeOrderByDisplayNameAsc("3611000000")

        assertThat(children).isNotEmpty()
        assertThat(children).allMatch { it.level == 3 }
    }

    companion object {
        private val REPRESENTATIVE_REGION_NAMES =
            listOf(
                "서울특별시",
                "부산광역시",
                "세종특별자치시",
                "서울특별시 강남구",
                "부산광역시 해운대구",
                "제주특별자치도 제주시",
                "서울특별시 강남구 역삼동",
            )
    }
}
