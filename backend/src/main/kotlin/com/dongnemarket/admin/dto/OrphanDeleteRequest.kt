package com.dongnemarket.admin.dto

/**
 * 고아파일 삭제 요청.
 *
 * `targets` 는 nullable 이다 — 원본 Java 가 `request.targets() == null` 을 방어하므로
 * 본문이 `{}` 인 요청에서도 빈 목록으로 동작해야 한다.
 *
 * 반면 [OrphanTarget] 의 두 필드는 non-null 이다. 이 값들은 그대로
 * FileStorageService.delete(filename, directory) 로 넘어가는데 그 시그니처가 non-null 이라,
 * nullable 로 두면 호출부에서 강제 언랩(!!)이 필요해진다. 필드가 빠진 요청은
 * 역직렬화 단계에서 400 으로 거부된다(원본은 INVALID_STORAGE_DIRECTORY 로 거부했다 — 둘 다 400).
 */
data class OrphanDeleteRequest(
    val targets: List<OrphanTarget>?,
) {
    /** Kotlin 의 중첩 클래스는 기본이 static nested 라 Java 의 중첩 record 와 같은 구조가 된다. */
    data class OrphanTarget(
        val directory: String,
        val filename: String,
    )
}
