package com.dongnemarket.mobile.data.remote

import com.dongnemarket.mobile.data.repository.RegionRepositoryImpl
import com.dongnemarket.mobile.di.NetworkModule
import com.dongnemarket.mobile.domain.repository.RegionRepository
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import com.dongnemarket.mobile.data.local.TokenDataStore

/**
 * 지역 API **계약 테스트**. 목을 쓰지 않고 진짜 Retrofit 을 가짜 서버([MockWebServer])에 붙인다.
 *
 * ## 저장소 단위 테스트가 못 보는 구간
 * `CatalogRepositoryImplTest` 는 `RegionApiService` 를 목으로 두므로 **직렬화 이후를 못 본다.**
 * `@Query("parentCode")` 애노테이션이 빠졌거나 이름이 틀려도 그쪽 테스트는 전부 초록이다.
 * 그런데 그 오타 하나면 서버가 파라미터를 못 읽어 **항상 최상위(시·도) 16건만** 돌려준다 —
 * 400 도 예외도 없이 "종로구를 눌렀는데 시·도 목록이 또 뜨는" 증상으로만 드러난다.
 */
class RegionApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: RegionRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        // DataStore 는 Android 의존이라 JVM 에서 못 쓴다 → 토큰 흐름만 목으로 대체한다.
        val tokenDataStore = mockk<TokenDataStore>()
        every { tokenDataStore.accessToken } returns flowOf("test-access-token")

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(NetworkModule.provideOkHttpClient(AuthInterceptor(tokenDataStore)))
            .addConverterFactory(
                NetworkModule.provideJson().asConverterFactory("application/json".toMediaType()),
            )
            .build()

        repository = RegionRepositoryImpl(retrofit.create(RegionApiService::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `최상위 조회에는 parentCode 파라미터가 아예 붙지 않는다`() = runTest {
        // Given
        server.enqueue(성공응답(시도_응답_JSON))

        // When
        repository.getRegions(parentCode = null)

        // Then: `?parentCode=` 처럼 빈 값이 붙어도 서버는 hasText 로 걸러 같은 결과를 주지만,
        // 파라미터가 통째로 빠지는 것이 계약이다.
        val path = server.takeRequest().path
        assertEquals("/api/regions", path)
    }

    @Test
    fun `하위 조회는 parentCode 를 쿼리로 실어 보낸다`() = runTest {
        // Given
        server.enqueue(성공응답(시군구_응답_JSON))

        // When
        repository.getRegions(parentCode = "11")

        // Then: 이름이 틀리면 서버가 못 읽고 **에러 없이 시·도 목록**을 돌려준다.
        // 드릴다운이 한 단계도 내려가지 않는 증상으로만 드러나는 유형이다.
        assertEquals("/api/regions?parentCode=11", server.takeRequest().path)
    }

    @Test
    fun `응답의 level 과 parentCode 가 도메인까지 그대로 흘러간다`() = runTest {
        // Given
        server.enqueue(성공응답(시군구_응답_JSON))

        // When
        val regions = repository.getRegions(parentCode = "11").getOrThrow()

        // Then: level 이 흘러가지 않으면 "고를 수 있는 단계" 판단이 통째로 무너진다
        val 종로구 = regions.single()
        assertEquals(2, 종로구.level)
        assertEquals("11", 종로구.parentCode)
        assertFalse("시·군·구는 고를 수 없다", 종로구.isSelectable)
    }

    @Test
    fun `읍면동만 선택 가능으로 판정된다`() = runTest {
        // Given
        server.enqueue(성공응답(읍면동_응답_JSON))

        // When
        val regions = repository.getRegions(parentCode = "1111").getOrThrow()

        // Then: 서버가 level 3 이 아닌 코드를 받으면 400 INVALID_INPUT_VALUE 다.
        // 그 판단을 화면이 하려면 level 이 정확히 도착해야 한다.
        assertTrue(regions.all { it.isSelectable })
        assertEquals(listOf("청운동", "신교동"), regions.map { it.displayName })
    }

    @Test
    fun `최상위 지역은 parentCode 가 null 로 온다`() = runTest {
        // Given
        server.enqueue(성공응답(시도_응답_JSON))

        // When
        val regions = repository.getRegions(parentCode = null).getOrThrow()

        // Then: `isTopLevel` 판단의 근거다. 빈 문자열로 오면 최상위 판정이 깨진다.
        assertTrue(regions.all { it.parentCode == null })
        assertTrue(regions.all { it.isTopLevel })
    }

    private fun 성공응답(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private val 시도_응답_JSON = """
        {
          "status": 200,
          "message": "요청이 성공적으로 처리되었습니다.",
          "data": [
            {"regionId": 1, "code": "11", "level": 1, "parentCode": null,
             "fullName": "서울특별시", "displayName": "서울특별시"},
            {"regionId": 2, "code": "26", "level": 1, "parentCode": null,
             "fullName": "부산광역시", "displayName": "부산광역시"}
          ]
        }
    """.trimIndent()

    private val 시군구_응답_JSON = """
        {
          "status": 200,
          "message": "요청이 성공적으로 처리되었습니다.",
          "data": [
            {"regionId": 17, "code": "1111", "level": 2, "parentCode": "11",
             "fullName": "서울특별시 종로구", "displayName": "종로구"}
          ]
        }
    """.trimIndent()

    private val 읍면동_응답_JSON = """
        {
          "status": 200,
          "message": "요청이 성공적으로 처리되었습니다.",
          "data": [
            {"regionId": 300, "code": "1111010100", "level": 3, "parentCode": "1111",
             "fullName": "서울특별시 종로구 청운동", "displayName": "청운동"},
            {"regionId": 301, "code": "1111010200", "level": 3, "parentCode": "1111",
             "fullName": "서울특별시 종로구 신교동", "displayName": "신교동"}
          ]
        }
    """.trimIndent()
}
