package com.dongnemarket.global.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * `file.storage.type=local`(기본값 — 값이 아예 없어도 선택된다)일 때 쓰이는 구현체.
 * `test` 프로파일(외부 인프라 없이 저장/조회 검증)과 온프레미스/로컬 디스크 배포 둘 다 이 구현체를 쓴다.
 * `directory`별로 `basePath` 하위에 폴더를 만들어 report-evidence/product-images를 분리한다.
 */
@Component
@ConditionalOnProperty(name = ["file.storage.type"], havingValue = "local", matchIfMissing = true)
class LocalFileStorageService(
    @Value("\${file.storage.local.base-path:./uploads}") basePath: String,
) : FileStorageService {
    private val basePath: Path = Paths.get(basePath).toAbsolutePath().normalize()

    override fun store(
        file: MultipartFile,
        directory: String,
    ): String {
        val dir = resolveDirectory(directory)
        try {
            Files.createDirectories(dir)
        } catch (e: IOException) {
            throw StorageException("저장 디렉터리를 생성할 수 없습니다: $dir", e)
        }

        val filename = UUID.randomUUID().toString() + extractExtension(file.originalFilename)
        val target = dir.resolve(filename)
        try {
            file.transferTo(target)
        } catch (e: IOException) {
            throw StorageException("파일 저장에 실패했습니다: $filename", e)
        }
        return filename
    }

    override fun load(
        filename: String,
        directory: String,
    ): Resource {
        val dir = resolveDirectory(directory)
        val target = dir.resolve(filename).normalize()
        // 경로 조작(path traversal) 방지: 정규화한 경로가 반드시 저장 디렉터리 내부여야 한다.
        if (!target.startsWith(dir)) {
            throw StorageFileNotFoundException(filename)
        }

        val resource = FileSystemResource(target)
        if (!resource.exists() || !resource.isReadable) {
            throw StorageFileNotFoundException(filename)
        }
        return resource
    }

    override fun list(directory: String): List<StoredObject> {
        val dir = resolveDirectory(directory)
        if (!Files.isDirectory(dir)) {
            return emptyList()
        }
        return try {
            // Java 의 try-with-resources 는 Kotlin 에서 use { } 확장 함수로 대체된다
            // (블록을 벗어날 때 close() 가 보장된다).
            Files.list(dir).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .map { toStoredObject(it) }
                    .toList()
            }
        } catch (e: IOException) {
            throw StorageException("저장 디렉터리를 나열할 수 없습니다: $dir", e)
        }
    }

    override fun delete(
        filename: String,
        directory: String,
    ): Boolean {
        val dir = resolveDirectory(directory)
        val target = dir.resolve(filename).normalize()
        // 경로 조작 방지: load()와 동일 규칙 — 정규화 경로가 저장 디렉터리 내부여야 한다.
        if (!target.startsWith(dir)) {
            throw StorageFileNotFoundException(filename)
        }
        return try {
            Files.deleteIfExists(target)
        } catch (e: IOException) {
            throw StorageException("파일 삭제에 실패했습니다: $filename", e)
        }
    }

    private fun toStoredObject(path: Path): StoredObject =
        try {
            StoredObject(
                path.fileName.toString(),
                Files.size(path),
                Files.getLastModifiedTime(path).toInstant(),
            )
        } catch (e: IOException) {
            throw StorageException("파일 메타데이터를 읽을 수 없습니다: $path", e)
        }

    private fun resolveDirectory(directory: String): Path = basePath.resolve(directory).toAbsolutePath().normalize()

    /**
     * `MultipartFile.getOriginalFilename()` 은 Spring 이 `@Nullable` 로 선언했다.
     * `-Xjsr305=strict` 덕분에 Kotlin 이 이걸 `String?` 로 읽으므로 파라미터도 nullable 로 받는다.
     */
    private fun extractExtension(originalFilename: String?): String {
        if (originalFilename == null) {
            return ""
        }
        val idx = originalFilename.lastIndexOf('.')
        return if (idx >= 0) originalFilename.substring(idx) else ""
    }
}
