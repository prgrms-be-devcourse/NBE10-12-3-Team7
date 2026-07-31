package com.dongnemarket.report.service

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.storage.LocalFileStorageService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path

class EvidenceImageStorageServiceTest {
    @TempDir
    lateinit var tempDir: Path

    lateinit var storageService: EvidenceImageStorageService

    @BeforeEach
    fun setUp() {
        storageService = EvidenceImageStorageService(LocalFileStorageService(tempDir.toString()))
    }

    @Test
    fun `이미지 파일을 저장하면 저장 디렉터리에 실제 파일이 생기고, 저장된 이름으로 다시 읽을 수 있다`() {
        val file = MockMultipartFile("file", "evidence.png", "image/png", "fake-image-bytes".toByteArray())

        val filename = storageService.store(file)

        assertThat(filename).endsWith(".png")
        assertThat(Files.exists(tempDir.resolve(DIRECTORY).resolve(filename))).isTrue()

        val loaded = storageService.load(filename)
        assertThat(loaded.exists()).isTrue()
        assertThat(loaded.inputStream.readAllBytes()).isEqualTo("fake-image-bytes".toByteArray())
    }

    @Test
    fun `빈 파일을 업로드하면 INVALID_EVIDENCE_IMAGE 예외가 발생한다`() {
        val file = MockMultipartFile("file", "empty.png", "image/png", ByteArray(0))

        val ex = assertThrows<BusinessException> { storageService.store(file) }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_EVIDENCE_IMAGE)
    }

    @Test
    fun `이미지가 아닌 파일(content-type)을 업로드하면 INVALID_EVIDENCE_IMAGE 예외가 발생한다`() {
        val file = MockMultipartFile("file", "malware.exe", "application/x-msdownload", "not an image".toByteArray())

        val ex = assertThrows<BusinessException> { storageService.store(file) }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_EVIDENCE_IMAGE)
    }

    @Test
    fun `5MB를 초과하는 파일을 업로드하면 INVALID_EVIDENCE_IMAGE 예외가 발생한다`() {
        val tooLarge = ByteArray(6 * 1024 * 1024)
        val file = MockMultipartFile("file", "big.png", "image/png", tooLarge)

        val ex = assertThrows<BusinessException> { storageService.store(file) }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_EVIDENCE_IMAGE)
    }

    @Test
    fun `존재하지 않는 파일명을 조회하면 EVIDENCE_IMAGE_NOT_FOUND 예외가 발생한다`() {
        val ex = assertThrows<BusinessException> { storageService.load("does-not-exist.png") }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.EVIDENCE_IMAGE_NOT_FOUND)
    }

    @Test
    fun `경로 조작 문자열(-- 등)로 조회하면 저장 디렉터리를 벗어나지 못하고 EVIDENCE_IMAGE_NOT_FOUND 예외가 발생한다`() {
        // 저장 디렉터리(tempDir/report-evidence) 바깥에 실제로 파일이 있어도 접근할 수 없어야 한다.
        val outsideFile = tempDir.resolve("secret.txt")
        Files.writeString(outsideFile, "should not be readable")

        val ex = assertThrows<BusinessException> { storageService.load("../secret.txt") }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.EVIDENCE_IMAGE_NOT_FOUND)
    }

    companion object {
        private const val DIRECTORY = "report-evidence"
    }
}
