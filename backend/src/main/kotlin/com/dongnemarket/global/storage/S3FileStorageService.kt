package com.dongnemarket.global.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.util.UUID

/**
 * `file.storage.type=s3`일 때 쓰이는 구현체. AWS S3에 저장한다.
 *
 * `directory`를 S3 키 prefix로 써서 report-evidence/product-images를 분리한다
 * (예: `product-images/3f2c...-a1.png`).
 */
@Component
@ConditionalOnProperty(name = ["file.storage.type"], havingValue = "s3")
class S3FileStorageService(
    private val s3Client: S3Client,
    @param:Value("\${file.storage.s3.bucket}") private val bucket: String,
) : FileStorageService {
    override fun store(
        file: MultipartFile,
        directory: String,
    ): String {
        val filename = UUID.randomUUID().toString() + extractExtension(file.originalFilename)
        val key = key(directory, filename)
        // Java 의 `catch (IOException | SdkException e)` 는 Kotlin 에 다중 catch 가 없어 절을 나눈다.
        try {
            val request =
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(file.contentType)
                    .build()
            s3Client.putObject(request, RequestBody.fromInputStream(file.inputStream, file.size))
        } catch (e: IOException) {
            throw StorageException("S3 업로드에 실패했습니다: $key", e)
        } catch (e: SdkException) {
            throw StorageException("S3 업로드에 실패했습니다: $key", e)
        }
        return filename
    }

    override fun load(
        filename: String,
        directory: String,
    ): Resource {
        // S3 키에는 파일시스템식 경로 탐색이 없지만, 방어적으로 디렉터리 이탈 문자를 거부한다.
        // (원본의 filename == null 검사는 파라미터가 non-null 타입이 되어 불필요해졌다.)
        if (filename.isBlank() || filename.contains("/") || filename.contains("..")) {
            throw StorageFileNotFoundException(filename)
        }

        val key = key(directory, filename)
        try {
            val bytes =
                s3Client
                    .getObjectAsBytes(
                        GetObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key(key)
                            .build(),
                    ).asByteArray()
            return ByteArrayResource(bytes)
        } catch (e: NoSuchKeyException) {
            throw StorageFileNotFoundException(filename)
        } catch (e: SdkException) {
            throw StorageException("S3 다운로드에 실패했습니다: $key", e)
        }
    }

    override fun list(directory: String): List<StoredObject> {
        val prefix = "$directory/"
        try {
            return s3Client
                .listObjectsV2Paginator(
                    ListObjectsV2Request
                        .builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .build(),
                ).contents()
                .filter { !it.key().endsWith("/") } // 디렉터리 플레이스홀더 제외
                .map {
                    StoredObject(
                        it.key().substring(prefix.length), // prefix 제거 → 순수 파일명
                        it.size(),
                        it.lastModified(),
                    )
                }
        } catch (e: SdkException) {
            throw StorageException("S3 목록 조회에 실패했습니다: $prefix", e)
        }
    }

    override fun delete(
        filename: String,
        directory: String,
    ): Boolean {
        // load()와 동일 방어: 디렉터리 이탈 문자 거부.
        if (filename.isBlank() || filename.contains("/") || filename.contains("..")) {
            throw StorageFileNotFoundException(filename)
        }
        val key = key(directory, filename)
        try {
            s3Client.headObject(
                HeadObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
            )
        } catch (e: NoSuchKeyException) {
            return false
        } catch (e: S3Exception) {
            // NoSuchKeyException 은 S3Exception 의 하위 타입이라 catch 순서가 중요하다(Java 와 동일).
            if (e.statusCode() == 404) {
                return false // HeadObject는 404를 NoSuchKey 대신 S3Exception로 줄 수 있어 함께 처리
            }
            throw StorageException("S3 조회에 실패했습니다: $key", e)
        }
        return try {
            s3Client.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
            )
            true
        } catch (e: SdkException) {
            throw StorageException("S3 삭제에 실패했습니다: $key", e)
        }
    }

    private fun key(
        directory: String,
        filename: String,
    ): String = "$directory/$filename"

    private fun extractExtension(originalFilename: String?): String {
        if (originalFilename == null) {
            return ""
        }
        val idx = originalFilename.lastIndexOf('.')
        return if (idx >= 0) originalFilename.substring(idx) else ""
    }
}
