package com.dongnemarket.admin.dto

/** 고아파일 삭제 결과 집계. */
data class OrphanDeleteResponse(
    val requested: Int,
    val deleted: Int,
    val skipped: Int,
)
