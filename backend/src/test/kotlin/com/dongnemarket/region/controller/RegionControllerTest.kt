package com.dongnemarket.region.controller

import com.dongnemarket.global.init.master.RegionSeeder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * [통합] 지역 REST — 전부 인증 없이 열려 있는 마스터 조회 API.
 * 세종은 시도(level 1) 바로 아래에 읍면동(level 3)이 붙는 예외 계층이라 별도로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegionControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var regionSeeder: RegionSeeder

    @BeforeEach
    fun setUp() {
        regionSeeder.seed()
    }

    @Test
    fun `인증 없이 최상위 지역 목록을 조회할 수 있다`() {
        mockMvc
            .perform(get("/api/regions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.length()").value(16))
            .andExpect(jsonPath("$.data[?(@.code == '1100000000')].displayName").value("서울특별시"))
    }

    @Test
    fun `인증 없이 부모 지역의 하위 지역 목록을 조회할 수 있다`() {
        mockMvc
            .perform(
                get("/api/regions")
                    .param("parentCode", "1100000000"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data[0].level").value(2))
            .andExpect(jsonPath("$.data[?(@.code == '1168000000')].displayName").value("강남구"))
    }

    @Test
    fun `세종은 시도 바로 아래 읍면동을 하위 지역으로 반환한다`() {
        mockMvc
            .perform(
                get("/api/regions")
                    .param("parentCode", "3611000000"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data[0].level").value(3))
    }
}
