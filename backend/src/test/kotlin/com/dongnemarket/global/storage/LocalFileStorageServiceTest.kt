package com.dongnemarket.global.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path

class LocalFileStorageServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var storageService: LocalFileStorageService

    @BeforeEach
    fun setUp() {
        storageService = LocalFileStorageService(tempDir.toString())
    }

    @Test
    fun `store()는 directory 하위에 UUID 파일명으로 실제 파일을 만들고, 저장한 파일명을 반환한다`() {
        val file = MockMultipartFile("file", "photo.png", "image/png", "content".toByteArray())

        val filename = storageService.store(file, DIRECTORY)

        assertThat(filename).endsWith(".png")
        assertThat(Files.exists(tempDir.resolve(DIRECTORY).resolve(filename))).isTrue()
    }

    @Test
    fun `store() 후 load()로 같은 디렉터리·파일명으로 다시 읽을 수 있다`() {
        val file = MockMultipartFile("file", "photo.png", "image/png", "content bytes".toByteArray())
        val filename = storageService.store(file, DIRECTORY)

        val loaded = storageService.load(filename, DIRECTORY)

        assertThat(loaded.exists()).isTrue()
        assertThat(loaded.inputStream.readAllBytes()).isEqualTo("content bytes".toByteArray())
    }

    @Test
    fun `서로 다른 directory는 서로 다른 하위 폴더에 저장되어 섞이지 않는다`() {
        val file = MockMultipartFile("file", "a.png", "image/png", "a".toByteArray())
        val filename = storageService.store(file, "product-images")

        assertThrows<StorageFileNotFoundException> { storageService.load(filename, "report-evidence") }
        assertThat(storageService.load(filename, "product-images")).isNotNull()
    }

    @Test
    fun `존재하지 않는 파일명을 조회하면 StorageFileNotFoundException이 발생한다`() {
        assertThrows<StorageFileNotFoundException> { storageService.load("does-not-exist.png", DIRECTORY) }
    }

    @Test
    fun `경로 조작 문자열로 조회하면 저장 디렉터리를 벗어나지 못하고 StorageFileNotFoundException이 발생한다`() {
        // 저장 디렉터리(tempDir/report-evidence) 바깥에 실제로 파일이 있어도 접근할 수 없어야 한다.
        Files.writeString(tempDir.resolve("secret.txt"), "should not be readable")

        assertThrows<StorageFileNotFoundException> { storageService.load("../secret.txt", DIRECTORY) }
    }

    companion object {
        private const val DIRECTORY = "report-evidence"
    }
}
