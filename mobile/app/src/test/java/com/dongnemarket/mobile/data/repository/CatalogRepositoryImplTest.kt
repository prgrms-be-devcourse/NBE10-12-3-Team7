package com.dongnemarket.mobile.data.repository

import com.dongnemarket.mobile.data.remote.CategoryApiService
import com.dongnemarket.mobile.data.remote.RegionApiService
import com.dongnemarket.mobile.data.remote.dto.ApiEnvelope
import com.dongnemarket.mobile.data.remote.dto.CategoryResponse
import com.dongnemarket.mobile.data.remote.dto.RegionResponse
import com.dongnemarket.mobile.domain.model.AppError
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 카테고리·지역(동네) 마스터 데이터 명세.
 *
 * 두 리포지토리의 존재 이유가 **세션 캐시**다.
 *  - 카테고리: 시드 8건 고정, 앱에서 바꿀 방법이 없다 → 홈에 들어올 때마다 받을 이유가 없다.
 *  - 지역: 한 번에 **229건**이 오는데 검색·페이징 파라미터가 아예 없다(계약 §2-6).
 *
 * 그리고 서버 키 이름이 비대칭이다 — 카테고리는 `id`, 지역은 `regionId`.
 * 이 번역을 매퍼가 책임진다.
 */
class CatalogRepositoryImplTest {

    private val categoryApi = mockk<CategoryApiService>()
    private val regionApi = mockk<RegionApiService>()

    // ────────────────────────── 카테고리 매핑 ──────────────────────────

    @Test
    fun `서버 키 id 가 도메인 Category 의 id 로 매핑된다`() = runTest {
        // Given — 카테고리 PK 키는 categoryId 가 아니라 id 다
        coEvery { categoryApi.getCategories() } returns
            envelope(listOf(CategoryResponse(id = 1L, name = "디지털기기")))
        val repository = CategoryRepositoryImpl(categoryApi)

        // When
        val categories = repository.getCategories().getOrThrow()

        // Then
        assertEquals(1L, categories.single().id)
    }

    @Test
    fun `카테고리 칩 순서는 서버가 준 시드 순서 그대로 유지된다`() = runTest {
        // Given — 정렬은 id ASC(시드 등록순)이고 앱이 재정렬하면 안 된다(계약 §8-8)
        coEvery { categoryApi.getCategories() } returns envelope(
            listOf(
                CategoryResponse(id = 1L, name = "디지털기기"),
                CategoryResponse(id = 2L, name = "생활가전"),
                CategoryResponse(id = 3L, name = "가구/인테리어"),
            ),
        )
        val repository = CategoryRepositoryImpl(categoryApi)

        // When
        val categories = repository.getCategories().getOrThrow()

        // Then — 가나다순으로 정렬해 버리면 "가구/인테리어" 가 맨 앞으로 온다
        assertEquals(listOf("디지털기기", "생활가전", "가구/인테리어"), categories.map { it.name })
    }

    // ────────────────────────── 카테고리 세션 캐시 ──────────────────────────

    @Test
    fun `카테고리를 두 번 조회해도 서버에는 한 번만 요청한다`() = runTest {
        // Given — 홈 재진입마다 같은 8건을 다시 받을 이유가 없다
        var callCount = 0
        coEvery { categoryApi.getCategories() } answers {
            callCount++
            envelope(listOf(CategoryResponse(id = 1L, name = "디지털기기")))
        }
        val repository = CategoryRepositoryImpl(categoryApi)

        // When
        repository.getCategories()
        repository.getCategories()

        // Then
        assertEquals(1, callCount)
    }

    @Test
    fun `캐시에서 돌려준 두 번째 결과도 첫 번째와 같은 내용이다`() = runTest {
        // Given
        coEvery { categoryApi.getCategories() } returns envelope(
            listOf(
                CategoryResponse(id = 1L, name = "디지털기기"),
                CategoryResponse(id = 2L, name = "생활가전"),
            ),
        )
        val repository = CategoryRepositoryImpl(categoryApi)

        // When
        val first = repository.getCategories().getOrThrow()
        val second = repository.getCategories().getOrThrow()

        // Then
        assertEquals(first, second)
    }

    @Test
    fun `첫 조회가 실패하면 캐시되지 않아 다음 조회에서 다시 요청한다`() = runTest {
        // Given — 실패를 캐시해 버리면 네트워크가 돌아와도 칩이 영영 안 나온다
        var callCount = 0
        coEvery { categoryApi.getCategories() } answers {
            callCount++
            if (callCount == 1) throw IOException("네트워크 끊김")
            envelope(listOf(CategoryResponse(id = 1L, name = "디지털기기")))
        }
        val repository = CategoryRepositoryImpl(categoryApi)

        // When
        repository.getCategories()
        repository.getCategories()

        // Then
        assertEquals(2, callCount)
    }

    @Test
    fun `실패한 뒤 다시 조회하면 정상적으로 카테고리를 받는다`() = runTest {
        // Given
        var callCount = 0
        coEvery { categoryApi.getCategories() } answers {
            callCount++
            if (callCount == 1) throw IOException("네트워크 끊김")
            envelope(listOf(CategoryResponse(id = 1L, name = "디지털기기")))
        }
        val repository = CategoryRepositoryImpl(categoryApi)

        // When
        repository.getCategories()
        val retried = repository.getCategories()

        // Then
        assertEquals(listOf("디지털기기"), retried.getOrThrow().map { it.name })
    }

    @Test
    fun `카테고리 조회 실패는 예외가 아니라 Network 오류 실패로 돌아온다`() = runTest {
        // Given
        coEvery { categoryApi.getCategories() } throws IOException("네트워크 끊김")
        val repository = CategoryRepositoryImpl(categoryApi)

        // When — 예외가 튀어 오르면 홈 화면 전체가 죽는다
        val result = repository.getCategories()

        // Then
        assertTrue(result.exceptionOrNull() is AppError.Network)
    }

    // ────────────────────────── 지역 매핑 ──────────────────────────

    @Test
    fun `서버 키 regionId 가 도메인 Region 의 regionId 로 매핑된다`() = runTest {
        // Given — 지역 PK 키는 카테고리와 달리 regionId 다
        coEvery { regionApi.getRegions() } returns
            envelope(listOf(RegionResponse(regionId = 11L, code = "11680", level = 2, parentCode = "11", fullName = "서울특별시 강남구", displayName = "강남구")))
        val repository = RegionRepositoryImpl(regionApi)

        // When
        val regions = repository.getRegions().getOrThrow()

        // Then
        assertEquals(11L, regions.single().regionId)
    }

    @Test
    fun `지역 이름은 공백까지 서버 원문 그대로 보존된다`() = runTest {
        // Given — 이 문자열이 그대로 상품 필터·내 동네 설정 요청에 실려 서버에서 완전 비교된다(계약 §7-18).
        //         여기서 trim 하거나 공백을 정규화하면 원인 불명 400 이 난다.
        coEvery { regionApi.getRegions() } returns
            envelope(listOf(RegionResponse(regionId = 11L, code = "00000", level = 2, parentCode = "11", fullName = " 서울  강남구 ", displayName = " 서울  강남구 ")))
        val repository = RegionRepositoryImpl(regionApi)

        // When
        val regions = repository.getRegions().getOrThrow()

        // Then
        assertEquals(" 서울  강남구 ", regions.single().displayName)
    }

    @Test
    fun `지역 목록 순서는 서버가 준 가나다 ASC 그대로 유지된다`() = runTest {
        // Given
        coEvery { regionApi.getRegions() } returns envelope(
            listOf(
                RegionResponse(regionId = 11L, code = "11680", level = 2, parentCode = "11", fullName = "서울특별시 강남구", displayName = "강남구"),
                RegionResponse(regionId = 12L, code = "11740", level = 2, parentCode = "11", fullName = "서울특별시 강동구", displayName = "강동구"),
                RegionResponse(regionId = 13L, code = "00000", level = 2, parentCode = "11", fullName = "서울 강북구", displayName = "서울 강북구"),
            ),
        )
        val repository = RegionRepositoryImpl(regionApi)

        // When
        val regions = repository.getRegions().getOrThrow()

        // Then
        assertEquals(listOf(11L, 12L, 13L), regions.map { it.regionId })
    }

    @Test
    fun `공백이 없는 세종은 시도 이름이 자기 자신이 된다`() = runTest {
        // Given — 229건 중 "세종" 만 공백이 없다. split(" ")[1] 로 접근하면 여기서 터진다.
        coEvery { regionApi.getRegions() } returns
            envelope(listOf(RegionResponse(regionId = 200L, code = "00000", level = 2, parentCode = "11", fullName = "세종", displayName = "세종")))
        val repository = RegionRepositoryImpl(regionApi)

        // When
        val region = repository.getRegions().getOrThrow().single()

        // Then — 동네 선택 화면의 섹션 헤더가 "세종" 으로 잡힌다
        assertEquals("세종", region.displayName)
    }

    // ────────────────────────── 지역 세션 캐시 ──────────────────────────

    @Test
    fun `동네 목록을 두 번 조회해도 229건 요청은 한 번만 나간다`() = runTest {
        // Given
        var callCount = 0
        coEvery { regionApi.getRegions() } answers {
            callCount++
            envelope(listOf(RegionResponse(regionId = 11L, code = "11680", level = 2, parentCode = "11", fullName = "서울특별시 강남구", displayName = "강남구")))
        }
        val repository = RegionRepositoryImpl(regionApi)

        // When — 동네 선택 화면에 들어갔다 나왔다 한 상황
        repository.getRegions()
        repository.getRegions()

        // Then
        assertEquals(1, callCount)
    }

    @Test
    fun `동시에 다섯 곳에서 동네 목록을 요청해도 서버 요청은 한 번뿐이다`() = runTest {
        // Given — 첫 응답이 도착하기 전에 다른 호출이 들어오는 상황(캐시 1차 확인만으로는 못 막는다)
        var callCount = 0
        coEvery { regionApi.getRegions() } coAnswers {
            callCount++
            delay(100)
            envelope(listOf(RegionResponse(regionId = 11L, code = "11680", level = 2, parentCode = "11", fullName = "서울특별시 강남구", displayName = "강남구")))
        }
        val repository = RegionRepositoryImpl(regionApi)

        // When
        coroutineScope {
            List(5) { async { repository.getRegions() } }.awaitAll()
        }

        // Then — Mutex + double-checked locking 이 중복 요청을 막는다
        assertEquals(1, callCount)
    }

    @Test
    fun `지역 조회 실패는 예외가 아니라 Network 오류 실패로 돌아온다`() = runTest {
        // Given
        coEvery { regionApi.getRegions() } throws IOException("네트워크 끊김")
        val repository = RegionRepositoryImpl(regionApi)

        // When
        val result = repository.getRegions()

        // Then
        assertTrue(result.exceptionOrNull() is AppError.Network)
    }

    @Test
    fun `지역 조회가 실패하면 캐시되지 않아 다음 조회에서 다시 요청한다`() = runTest {
        // Given
        var callCount = 0
        coEvery { regionApi.getRegions() } answers {
            callCount++
            if (callCount == 1) throw IOException("네트워크 끊김")
            envelope(listOf(RegionResponse(regionId = 11L, code = "11680", level = 2, parentCode = "11", fullName = "서울특별시 강남구", displayName = "강남구")))
        }
        val repository = RegionRepositoryImpl(regionApi)

        // When
        repository.getRegions()
        repository.getRegions()

        // Then
        assertEquals(2, callCount)
    }

    // ────────────────────────── 테스트용 응답 조립 ──────────────────────────

    private fun <T : Any> envelope(data: T) =
        ApiEnvelope(status = 200, message = "요청이 성공적으로 처리되었습니다.", data = data)
}
