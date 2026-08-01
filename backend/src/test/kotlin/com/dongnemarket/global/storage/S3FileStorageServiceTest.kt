package com.dongnemarket.global.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.mock.web.MockMultipartFile
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest

@ExtendWith(MockitoExtension::class)
class S3FileStorageServiceTest {
    @Mock
    lateinit var s3Client: S3Client

    private lateinit var storageService: S3FileStorageService

    @BeforeEach
    fun setUp() {
        storageService = S3FileStorageService(s3Client, BUCKET)
    }

    @Test
    fun `store()는 UUID 기반 파일명으로 directory 파일명 키에 putObject를 호출하고, 파일명을 반환한다`() {
        val file = MockMultipartFile("file", "photo.png", "image/png", "content".toByteArray())

        val filename = storageService.store(file, DIRECTORY)

        assertThat(filename).endsWith(".png")

        val captor = ArgumentCaptor.forClass(PutObjectRequest::class.java)
        verify(s3Client).putObject(captor.capture(), any(RequestBody::class.java))
        val request = captor.value
        assertThat(request.bucket()).isEqualTo(BUCKET)
        assertThat(request.key()).isEqualTo("$DIRECTORY/$filename")
        assertThat(request.contentType()).isEqualTo("image/png")
    }

    @Test
    fun `putObject 중 SdkException이 발생하면 StorageException으로 변환한다`() {
        val file = MockMultipartFile("file", "photo.png", "image/png", "content".toByteArray())
        given(s3Client.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java)))
            .willThrow(SdkException.builder().message("network error").build())

        assertThrows<StorageException> { storageService.store(file, DIRECTORY) }
    }

    @Test
    fun `load()는 directory 파일명 키로 조회해 Resource로 반환한다`() {
        val content = "image bytes".toByteArray()
        val responseBytes = ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content)
        given(s3Client.getObjectAsBytes(any(GetObjectRequest::class.java))).willReturn(responseBytes)

        val resource = storageService.load("existing.png", DIRECTORY)

        assertThat(resource.contentAsByteArray).isEqualTo(content)
        val captor = ArgumentCaptor.forClass(GetObjectRequest::class.java)
        verify(s3Client).getObjectAsBytes(captor.capture())
        assertThat(captor.value.bucket()).isEqualTo(BUCKET)
        assertThat(captor.value.key()).isEqualTo("$DIRECTORY/existing.png")
    }

    @Test
    fun `존재하지 않는 키를 조회하면 NoSuchKeyException을 StorageFileNotFoundException으로 변환한다`() {
        given(s3Client.getObjectAsBytes(any(GetObjectRequest::class.java)))
            .willThrow(NoSuchKeyException.builder().message("not found").build())

        assertThrows<StorageFileNotFoundException> { storageService.load("missing.png", DIRECTORY) }
    }

    @Test
    fun `S3 조회 중 그 외 SdkException이 발생하면 StorageException으로 변환한다`() {
        given(s3Client.getObjectAsBytes(any(GetObjectRequest::class.java)))
            .willThrow(SdkException.builder().message("throttled").build())

        assertThrows<StorageException> { storageService.load("existing.png", DIRECTORY) }
    }

    @Test
    fun `파일명에 경로 이탈 문자가 있으면 S3를 호출하지 않고 바로 StorageFileNotFoundException을 던진다`() {
        assertThrows<StorageFileNotFoundException> { storageService.load("../secret.png", DIRECTORY) }
        assertThrows<StorageFileNotFoundException> { storageService.load("sub/dir.png", DIRECTORY) }

        verify(s3Client, never()).getObjectAsBytes(any(GetObjectRequest::class.java))
    }

    companion object {
        private const val BUCKET = "test-bucket"
        private const val DIRECTORY = "product-images"
    }
}
