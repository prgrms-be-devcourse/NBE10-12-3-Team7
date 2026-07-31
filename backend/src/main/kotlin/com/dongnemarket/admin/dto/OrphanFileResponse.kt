package com.dongnemarket.admin.dto

import java.time.Instant

/** 고아파일 한 건의 메타데이터. */
data class OrphanFileResponse(
    val directory: String,
    val filename: String,
    val sizeBytes: Long,
    val lastModified: Instant,
    val ageHours: Long,
)
