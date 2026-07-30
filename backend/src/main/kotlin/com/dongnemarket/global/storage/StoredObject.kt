package com.dongnemarket.global.storage

import java.time.Instant

/**
 * 저장소에 실재하는 파일 하나의 메타데이터.
 *
 * Java `record` → Kotlin `data class`. **접근자 이름이 바뀐다**:
 * `filename()` → `getFilename()`. record 는 접근자에 `get` 접두어를 붙이지 않지만
 * data class 는 일반 프로퍼티라 `getXxx()` 가 된다 → 아직 Java 인 호출부를 함께 고쳐야 한다.
 */
data class StoredObject(
    val filename: String,
    val sizeBytes: Long,
    val lastModified: Instant,
)
