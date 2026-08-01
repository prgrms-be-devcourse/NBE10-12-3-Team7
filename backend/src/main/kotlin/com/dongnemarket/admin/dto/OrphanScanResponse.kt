package com.dongnemarket.admin.dto

/** 고아파일 스캔 결과. 목록과 함께 총 건수·총 용량·적용된 grace 시간을 담는다. */
data class OrphanScanResponse(
    val orphans: List<OrphanFileResponse>,
    val totalCount: Int,
    val totalBytes: Long,
    val graceHours: Long,
) {
    companion object {
        /** Java 의 `stream().mapToLong(...).sum()` 이 sumOf 하나로 줄어든다. */
        @JvmStatic
        fun of(
            orphans: List<OrphanFileResponse>,
            graceHours: Long,
        ): OrphanScanResponse =
            OrphanScanResponse(
                orphans,
                orphans.size,
                orphans.sumOf { it.sizeBytes },
                graceHours,
            )
    }
}
