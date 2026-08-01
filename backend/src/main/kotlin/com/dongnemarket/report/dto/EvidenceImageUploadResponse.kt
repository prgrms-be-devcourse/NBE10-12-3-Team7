package com.dongnemarket.report.dto

@ConsistentCopyVisibility
data class EvidenceImageUploadResponse private constructor(
    val evidenceImageUrl: String,
) {
    companion object {
        @JvmStatic
        fun of(evidenceImageUrl: String): EvidenceImageUploadResponse = EvidenceImageUploadResponse(evidenceImageUrl)
    }
}
