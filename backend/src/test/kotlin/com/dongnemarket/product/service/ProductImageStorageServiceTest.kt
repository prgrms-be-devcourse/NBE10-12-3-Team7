package com.dongnemarket.product.service

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.storage.LocalFileStorageService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * [단위] 상품 이미지 저장 서비스 — 저장/조회와 업로드 검증 규칙(개수·빈 파일·MIME·용량).
 * 조회는 경로 탈출(`../`)을 막아야 하므로 저장 경로 밖 요청도 PRODUCT_NOT_FOUND 로 떨어지는지 확인한다.
 */
class ProductImageStorageServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `이미지 파일을 저장하면 상품 이미지 URL 목록을 반환한다`() {
        val storageService = newStorageService()
        val firstFile = imageFile("first.jpg", "image/jpeg", "first image")
        val secondFile = imageFile("second.png", "image/png", "second image")

        val imageUrls = storageService.store(listOf(firstFile, secondFile))

        assertThat(imageUrls).hasSize(2)
        assertThat(imageUrls).allMatch { url -> url.startsWith(IMAGE_URL_PREFIX) }
        for (imageUrl in imageUrls) {
            val filename = imageUrl.substring(IMAGE_URL_PREFIX.length)
            assertThat(Files.exists(tempDir.resolve(DIRECTORY).resolve(filename))).isTrue()
        }
    }

    @Test
    fun `저장된 상품 이미지를 Resource로 조회할 수 있다`() {
        val storageService = newStorageService()
        val imageUrls = storageService.store(listOf(imageFile("product.webp", "image/webp", "image content")))
        val filename = imageUrls[0].substring(IMAGE_URL_PREFIX.length)

        val resource = storageService.load(filename)

        assertThat(resource.exists()).isTrue()
        assertThat(resource.contentAsByteArray).isEqualTo("image content".toByteArray())
    }

    @Test
    fun `이미지 파일이 없으면 저장할 수 없다`() {
        val storageService = newStorageService()

        assertInvalidInput { storageService.store(emptyList()) }
    }

    @Test
    fun `상품 이미지는 최대 5장까지만 저장할 수 있다`() {
        val storageService = newStorageService()
        val files: List<MultipartFile> =
            listOf(
                imageFile("1.jpg", "image/jpeg", "1"),
                imageFile("2.jpg", "image/jpeg", "2"),
                imageFile("3.jpg", "image/jpeg", "3"),
                imageFile("4.jpg", "image/jpeg", "4"),
                imageFile("5.jpg", "image/jpeg", "5"),
                imageFile("6.jpg", "image/jpeg", "6"),
            )

        assertInvalidInput { storageService.store(files) }
    }

    @Test
    fun `빈 파일은 저장할 수 없다`() {
        val storageService = newStorageService()
        val emptyFile = MockMultipartFile("files", "empty.png", "image/png", ByteArray(0))

        assertInvalidInput { storageService.store(listOf(emptyFile)) }
    }

    @Test
    fun `이미지가 아닌 파일은 저장할 수 없다`() {
        val storageService = newStorageService()
        val textFile = MockMultipartFile("files", "memo.txt", "text/plain", "memo".toByteArray())

        assertInvalidInput { storageService.store(listOf(textFile)) }
    }

    @Test
    fun `5MB를 초과하는 파일은 저장할 수 없다`() {
        val storageService = newStorageService()
        val tooLarge = ByteArray(5 * 1024 * 1024 + 1)
        val largeFile = MockMultipartFile("files", "large.png", "image/png", tooLarge)

        assertInvalidInput { storageService.store(listOf(largeFile)) }
    }

    @Test
    fun `존재하지 않는 상품 이미지는 조회할 수 없다`() {
        val storageService = newStorageService()

        assertProductNotFound { storageService.load("missing.png") }
    }

    @Test
    fun `저장 경로 밖 파일은 조회할 수 없다`() {
        val storageService = newStorageService()

        assertProductNotFound { storageService.load("../secret.png") }
    }

    private fun newStorageService(): ProductImageStorageService = ProductImageStorageService(LocalFileStorageService(tempDir.toString()))

    private fun imageFile(
        filename: String,
        contentType: String,
        content: String,
    ): MockMultipartFile = MockMultipartFile("files", filename, contentType, content.toByteArray())

    private fun assertInvalidInput(callable: ThrowingCallable) {
        assertThatThrownBy(callable)
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE)
    }

    private fun assertProductNotFound(callable: ThrowingCallable) {
        assertThatThrownBy(callable)
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_NOT_FOUND)
    }

    companion object {
        private const val DIRECTORY = "product-images"
        private const val IMAGE_URL_PREFIX = "/api/products/images/"
    }
}
