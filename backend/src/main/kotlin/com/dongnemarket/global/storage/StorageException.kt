package com.dongnemarket.global.storage

/**
 * [FileStorageService.store]/[FileStorageService.load] 수행 중 I/O 또는 저장소(S3 등)
 * 장애로 실패했을 때 던진다. 도메인 어댑터가 이 예외를 잡아 자기 도메인의 BusinessException/ErrorCode로 변환한다.
 */
class StorageException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
