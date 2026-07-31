package com.dongnemarket.global.init.master

import com.dongnemarket.global.init.DataSeeder
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * 기준데이터: 계층형 전국 지역 마스터. 모든 환경에서 항상 실행(멱등).
 *
 * CSV 를 읽어 level 1(시·도) → 2(시·군·구) → 3(읍·면·동) 순으로 저장한다.
 * **level 순서가 중요하다** — 하위 지역이 부모 Region 객체를 참조하므로 부모가 먼저 저장돼야 한다.
 * 이미 있는 코드는 건너뛰므로 여러 번 실행해도 중복되지 않는다.
 */
@Component
class RegionSeeder(
    private val regionRepository: RegionRepository,
) : DataSeeder {
    override fun order(): Int = 11

    @Transactional
    override fun seed() {
        val rows = readRows()
        // 이미 저장된 지역을 코드로 색인해 둔다. 아래 루프에서 부모를 찾고, 새로 저장한 것도 여기에 넣는다.
        val savedRegionsByCode =
            regionRepository
                .findAllByCodeIn(rows.map { it.code })
                .associateBy { it.code }
                .toMutableMap()
        val existingCodes = savedRegionsByCode.keys.toMutableSet()

        for (level in 1..3) {
            val regions =
                rowsByLevel(rows, level)
                    .filterNot { it.code in existingCodes }
                    .map { row -> createRegion(row, row.parentCode?.let(savedRegionsByCode::get)) }

            regionRepository.saveAll(regions).forEach { region ->
                savedRegionsByCode[region.code] = region
                existingCodes += region.code
            }
        }
    }

    private fun rowsByLevel(
        rows: List<RegionSeedRow>,
        level: Int,
    ): List<RegionSeedRow> = rows.filter { it.level == level }.sortedBy { it.code }

    private fun createRegion(
        row: RegionSeedRow,
        parent: Region?,
    ): Region =
        if (row.level == 1) {
            Region.root(row.code, row.fullName, row.displayName)
        } else {
            Region.child(row.code, row.level, parent, row.fullName, row.displayName)
        }

    /**
     * CSV 를 읽어 행 목록으로. `use` 가 예외 여부와 무관하게 reader 를 닫는다(try-with-resources 대체).
     *
     * `lineSequence()` 는 지연 평가라 **반드시 `use` 블록 안에서 `toList()` 로 소비**해야 한다.
     * 밖으로 내보내면 이미 닫힌 스트림을 읽게 된다.
     */
    private fun readRows(): List<RegionSeedRow> {
        val resource = ClassPathResource(REGION_SEED_PATH)
        try {
            return resource.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader
                    .lineSequence()
                    .drop(1) // 헤더
                    .filter { it.isNotBlank() }
                    .map(::parseRow)
                    .toList()
            }
        } catch (e: IOException) {
            throw IllegalStateException("지역 시드 CSV를 읽을 수 없습니다: $REGION_SEED_PATH", e)
        }
    }

    private fun parseRow(line: String): RegionSeedRow {
        // Kotlin 의 split 은 뒤쪽 빈 문자열을 버리지 않는다(Java 의 split(",", -1) 과 동일).
        val values = line.split(",")
        check(values.size == 5) { "지역 시드 CSV 형식이 올바르지 않습니다: $line" }
        return RegionSeedRow(
            code = values[0],
            level = values[1].toInt(),
            parentCode = values[2].ifBlank { null },
            fullName = values[3],
            displayName = values[4],
        )
    }

    /** Kotlin 의 중첩 클래스는 기본이 static 이라 Java 의 중첩 record 와 같은 구조가 된다. */
    private data class RegionSeedRow(
        val code: String,
        val level: Int,
        val parentCode: String?,
        val fullName: String,
        val displayName: String,
    )

    companion object {
        private const val REGION_SEED_PATH = "seed/region_seed.csv"
    }
}
