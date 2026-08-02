package com.dongnemarket.mobile.data.remote

import com.dongnemarket.mobile.data.local.TokenDataStore
import com.dongnemarket.mobile.data.repository.AuthRepositoryImpl
import com.dongnemarket.mobile.data.repository.MemberRepositoryImpl
import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.di.NetworkModule
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * auth / member 계약 통합 테스트 — 목(mock) Repository 가 아니라
 * **진짜 Retrofit + OkHttp + kotlinx.serialization 을 가짜 서버(MockWebServer)에 붙여** 검증한다.
 *
 * 여기서 확인하는 것은 "우리 코드가 서버와 주고받기로 한 약속을 실제로 지키는가"다.
 *  - 나가는 것: 경로·메서드·요청 본문 키·Authorization 헤더
 *  - 들어오는 것: 껍데기(ApiEnvelope) 벗기기·data 없는 성공·에러 → AppError 번역·미지 필드 내성
 *
 * TokenDataStore 만 목이다. 실제 구현이 Android DataStore(파일 I/O)라 JVM 테스트에서 돌지 않기 때문이며,
 * 그 외 배관은 전부 프로덕션과 같은 실물이다(Json 은 NetworkModule.provideJson() 을 직접 호출한다).
 */
class AuthApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenDataStore: TokenDataStore
    private lateinit var authApi: AuthApiService
    private lateinit var memberApi: MemberApiService
    private lateinit var authRepository: AuthRepositoryImpl
    private lateinit var memberRepository: MemberRepositoryImpl

    /**
     * **제품 코드의 Json 을 그대로 쓴다.** 손으로 베껴 두면 NetworkModule 이 바뀌었을 때
     * 조용히 어긋나서(실제로 `encodeDefaults` 누락으로 그런 일이 있었다) 이 테스트가
     * 프로덕션이 아닌 사본을 검증하게 된다.
     */
    private val json = NetworkModule.provideJson()

    @Before
    fun `가짜 서버와 실제 네트워크 배관을 세운다`() {
        server = MockWebServer()
        server.start()

        // 기기에 "test-token" 이 저장돼 있는 상태를 가정한다.
        tokenDataStore = mockk(relaxed = true)
        every { tokenDataStore.accessToken } returns flowOf("test-token")

        val retrofit = retrofitBackedBy(tokenDataStore)
        authApi = retrofit.create(AuthApiService::class.java)
        memberApi = retrofit.create(MemberApiService::class.java)
        authRepository = AuthRepositoryImpl(authApi, tokenDataStore)
        memberRepository = MemberRepositoryImpl(memberApi)
    }

    @After
    fun `가짜 서버를 내린다`() {
        runCatching { server.shutdown() }
    }

    // ────────────────────────────────────────────────────────────────
    // 1. 나가는 요청 — 경로·메서드·본문
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `로그인은 POST 로 api 슬래시 auth 슬래시 login 에 보내진다`() = runTest {
        // Given: 서버가 로그인 성공을 준비한다
        server.enqueue(jsonResponse(200, LOGIN_SUCCESS_BODY))

        // When: 사용자가 로그인한다
        authRepository.login(email = "buyer@example.com", password = "Passw0rd!!")

        // Then: 계약(§1-1)대로 POST /api/auth/login 이 나갔다
        val recorded = server.takeRequest()
        assertEquals("POST /api/auth/login", "${recorded.method} ${recorded.path}")
    }

    @Test
    fun `로그인 요청 본문에 이메일과 비밀번호가 담긴다`() = runTest {
        // Given
        server.enqueue(jsonResponse(200, LOGIN_SUCCESS_BODY))

        // When
        authRepository.login(email = "buyer@example.com", password = "Passw0rd!!")

        // Then
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            "buyer@example.com:Passw0rd!!",
            "${body["email"]?.jsonPrimitive?.content}:${body["password"]?.jsonPrimitive?.content}",
        )
    }

    @Test
    fun `로그인 요청 본문에 autoLogin true 가 담긴다`() = runTest {
        // Given: 계약 §0.5 — autoLogin 이 빠지거나 false 면 서버가 refreshToken 을
        //        Max-Age 없는 세션 쿠키로 내려 보내 앱 재시작 시 자동 로그인이 끊긴다.
        server.enqueue(jsonResponse(200, LOGIN_SUCCESS_BODY))

        // When
        authRepository.login(email = "buyer@example.com", password = "Passw0rd!!")

        // Then
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            "autoLogin 이 요청 본문에 실제로 실려 나가야 한다. 본문=$body",
            "true",
            body["autoLogin"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `이메일 앞뒤 공백은 잘라서 보낸다`() = runTest {
        // Given: 키보드 자동완성이 붙인 공백은 서버 @Email 검증에서 400 이 된다
        server.enqueue(jsonResponse(200, LOGIN_SUCCESS_BODY))

        // When
        authRepository.login(email = "  buyer@example.com  ", password = "Passw0rd!!")

        // Then
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("buyer@example.com", body["email"]?.jsonPrimitive?.content)
    }

    @Test
    fun `비밀번호의 공백은 유효 문자이므로 자르지 않고 그대로 보낸다`() = runTest {
        // Given
        server.enqueue(jsonResponse(200, LOGIN_SUCCESS_BODY))

        // When
        authRepository.login(email = "buyer@example.com", password = " pass word ")

        // Then
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(" pass word ", body["password"]?.jsonPrimitive?.content)
    }

    @Test
    fun `내 동네 저장은 PUT 으로 regionCodes 배열을 보낸다`() = runTest {
        // Given
        server.enqueue(jsonResponse(200, LOCATIONS_SUCCESS_BODY))

        // When
        memberRepository.updateMyLocations(listOf("11680", "11440"))

        // Then: 계약 §2-7 — PUT /api/members/me/locations, body 는 {"regionCodes":[...]}
        val recorded = server.takeRequest()
        assertEquals(
            "PUT /api/members/me/locations {\"regionCodes\":[\"11680\",\"11440\"]}",
            "${recorded.method} ${recorded.path} ${recorded.body.readUtf8()}",
        )
    }

    // ────────────────────────────────────────────────────────────────
    // 2. AuthInterceptor — Authorization 헤더 부착 규칙
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `인증이 필요한 요청에는 Authorization Bearer 헤더가 붙는다`() = runTest {
        // Given: 기기에 "test-token" 이 저장돼 있다
        server.enqueue(jsonResponse(200, MY_PROFILE_SUCCESS_BODY))

        // When: 내 정보를 조회한다
        memberRepository.getMyProfile()

        // Then: 각 ApiService 가 @Header 를 쓰지 않아도 인터셉터가 붙여 준다
        assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `로그인 요청에는 Authorization 헤더가 붙지 않는다`() = runTest {
        // Given: 토큰이 저장돼 있어도 /api/auth/login 은 인터셉터의 예외 경로다.
        //        만료된 토큰을 로그인에 붙여 보내면 서버가 로그인 자체를 401 로 막는다.
        server.enqueue(jsonResponse(200, LOGIN_SUCCESS_BODY))

        // When
        authRepository.login(email = "buyer@example.com", password = "Passw0rd!!")

        // Then
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `저장된 토큰이 없으면 Authorization 헤더를 붙이지 않는다`() = runTest {
        // Given: 로그아웃 상태(토큰 null)
        val emptyStore = mockk<TokenDataStore>(relaxed = true)
        every { emptyStore.accessToken } returns flowOf<String?>(null)
        val anonymousMemberApi = retrofitBackedBy(emptyStore).create(MemberApiService::class.java)
        server.enqueue(jsonResponse(200, MY_PROFILE_SUCCESS_BODY))

        // When
        apiCall { anonymousMemberApi.getMyProfile() }

        // Then: "Bearer null" 같은 쓰레기 헤더가 나가면 안 된다
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    // ────────────────────────────────────────────────────────────────
    // 3. 들어오는 응답 — 껍데기 벗기기
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `로그인 성공하면 껍데기를 벗긴 accessToken 이 기기에 저장된다`() = runTest {
        // Given: {"status":200,"message":"...","data":{"accessToken":"eyJ..."}}
        server.enqueue(jsonResponse(200, LOGIN_SUCCESS_BODY))

        // When
        authRepository.login(email = "buyer@example.com", password = "Passw0rd!!")

        // Then: status·message·data 껍데기가 아니라 토큰 문자열만 저장된다
        coVerify { tokenDataStore.saveAccessToken("eyJhbGciOiJIUzI1NiJ9.access.signature") }
    }

    @Test
    fun `내 정보 응답의 data 가 도메인 모델로 벗겨진다`() = runTest {
        // Given
        server.enqueue(jsonResponse(200, MY_PROFILE_SUCCESS_BODY))

        // When
        val result = memberRepository.getMyProfile()

        // Then: 채팅 말풍선 좌우 판정의 기준이 되는 memberId 가 살아서 도착한다
        assertEquals(7L, result.getOrNull()?.memberId)
    }

    @Test
    fun `내 동네가 미설정이면 빈 배열 응답을 빈 리스트 성공으로 처리한다`() = runTest {
        // Given: 계약 §1-3 — 미설정 회원은 data 가 [] 로 온다(null 이 아니다)
        server.enqueue(jsonResponse(200, LOCATIONS_EMPTY_BODY))

        // When
        val result = memberRepository.getMyLocations()

        // Then: 빈 목록은 실패가 아니라 "동네 미설정" 이라는 정상 상태다
        assertEquals(emptyList<Any>(), result.getOrNull())
    }

    @Test
    fun `내 동네 목록에서 active 인 동네 이름을 뽑아낸다`() = runTest {
        // Given: 강남구(active=true) + 마포구(active=false)
        server.enqueue(jsonResponse(200, LOCATIONS_SUCCESS_BODY))

        // When
        val result = memberRepository.getActiveRegionName()

        // Then: 홈 헤더에 찍을 대표 동네
        assertEquals("강남구", result.getOrNull())
    }

    @Test
    fun `로그아웃 응답에 data 키가 아예 없어도 성공으로 처리한다`() = runTest {
        // Given: 계약 §1-5 — {"status":200,"message":"..."} 뿐이다(@JsonInclude(NON_NULL))
        server.enqueue(jsonResponse(200, NO_DATA_SUCCESS_BODY))

        // When
        val result = apiCallForUnit { authApi.logout() }

        // Then: data 없음을 실패로 판정하면 성공한 로그아웃이 실패가 된다
        assertTrue("data 키 없는 200 은 성공이어야 한다: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun `data 키가 필요한 응답에서 data 가 비면 EmptyBody 실패가 된다`() = runTest {
        // Given: 계약 위반 — 내 정보인데 data 가 없다
        server.enqueue(jsonResponse(200, NO_DATA_SUCCESS_BODY))

        // When
        val result = memberRepository.getMyProfile()

        // Then: 조용히 빈 값을 만들지 않고 계약 위반으로 드러낸다
        assertTrue(
            "실제: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is AppError.EmptyBody,
        )
    }

    // ────────────────────────────────────────────────────────────────
    // 4. 에러 응답 → AppError 번역
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `비밀번호 오류 401 은 Unauthorized 로 번역된다`() = runTest {
        // Given
        server.enqueue(jsonResponse(401, INVALID_PASSWORD_ERROR_BODY))

        // When
        val result = authRepository.login(email = "buyer@example.com", password = "wrong")

        // Then
        assertTrue(
            "실제: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is AppError.Unauthorized,
        )
    }

    @Test
    fun `401 응답의 서버 message 가 사용자 문구로 그대로 전달된다`() = runTest {
        // Given
        server.enqueue(jsonResponse(401, INVALID_PASSWORD_ERROR_BODY))

        // When
        val result = authRepository.login(email = "buyer@example.com", password = "wrong")

        // Then
        assertEquals(
            "비밀번호가 올바르지 않습니다.",
            (result.exceptionOrNull() as AppError).userMessage,
        )
    }

    @Test
    fun `백엔드 error 코드는 사용자 문구에 섞이지 않는다`() = runTest {
        // Given: 서버는 error="INVALID_PASSWORD" 라는 개발자 용어를 함께 준다
        server.enqueue(jsonResponse(401, INVALID_PASSWORD_ERROR_BODY))

        // When
        val result = authRepository.login(email = "buyer@example.com", password = "wrong")

        // Then: 화면에 노출되는 문장에는 enum 이름이 새어 나오면 안 된다
        val userMessage = (result.exceptionOrNull() as AppError).userMessage
        assertFalse("사용자 문구: $userMessage", userMessage.contains("INVALID_PASSWORD"))
    }

    @Test
    fun `미가입 이메일 404 는 Api 에러로 번역되며 상태코드를 보존한다`() = runTest {
        // Given: 계약 §1-1 — 미가입 이메일은 401 이 아니라 404 MEMBER_NOT_FOUND 다
        server.enqueue(jsonResponse(404, MEMBER_NOT_FOUND_ERROR_BODY))

        // When
        val result = authRepository.login(email = "ghost@example.com", password = "Passw0rd!!")

        // Then
        assertEquals(404, (result.exceptionOrNull() as? AppError.Api)?.status)
    }

    @Test
    fun `404 응답의 백엔드 error 코드는 분기용으로 보존된다`() = runTest {
        // Given
        server.enqueue(jsonResponse(404, MEMBER_NOT_FOUND_ERROR_BODY))

        // When
        val result = authRepository.login(email = "ghost@example.com", password = "Passw0rd!!")

        // Then: 화면에는 안 쓰지만 로그·분기에는 필요하다
        assertEquals("MEMBER_NOT_FOUND", (result.exceptionOrNull() as? AppError.Api)?.code)
    }

    @Test
    fun `로그인 5회 실패 후의 429 는 Api 에러로 번역된다`() = runTest {
        // Given: 계약 §1-1 — 5회 실패 시 10분 잠금
        server.enqueue(jsonResponse(429, TOO_MANY_ATTEMPTS_ERROR_BODY))

        // When
        val result = authRepository.login(email = "buyer@example.com", password = "wrong")

        // Then: 401 로 뭉뚱그리면 "다시 로그인" 안내가 나가 사용자를 잠금 상태로 계속 몰아넣는다
        assertEquals(429, (result.exceptionOrNull() as? AppError.Api)?.status)
    }

    @Test
    fun `서버 500 은 Api 에러로 번역되고 예외가 밖으로 튀지 않는다`() = runTest {
        // Given
        server.enqueue(jsonResponse(500, INTERNAL_SERVER_ERROR_BODY))

        // When
        val result = memberRepository.getMyProfile()

        // Then
        assertEquals(500, (result.exceptionOrNull() as? AppError.Api)?.status)
    }

    @Test
    fun `에러 본문이 스프링 기본 형식이어도 파싱에 실패하지 않는다`() = runTest {
        // Given: 매핑 없는 URL 의 404 는 우리 ErrorResponse 가 아니라 스프링 기본 바디다
        server.enqueue(jsonResponse(404, SPRING_DEFAULT_404_BODY))

        // When
        val result = memberRepository.getMyProfile()

        // Then: error 는 "Not Found" 라는 reason phrase 로 들어온다(enum 강제 변환 금지)
        assertEquals("Not Found", (result.exceptionOrNull() as? AppError.Api)?.code)
    }

    // ────────────────────────────────────────────────────────────────
    // 5. 파싱 내성
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `서버가 계약에 없는 필드를 추가해도 파싱이 깨지지 않는다`() = runTest {
        // Given: 백엔드가 profileImageUrl·newField 를 새로 추가한 상황
        server.enqueue(jsonResponse(200, MY_PROFILE_WITH_NEW_FIELDS_BODY))

        // When
        val result = memberRepository.getMyProfile()

        // Then: ignoreUnknownKeys 덕에 모르는 키는 버려지고 나머지는 정상 파싱된다
        assertEquals("동네주민", result.getOrNull()?.nickname)
    }

    @Test
    fun `응답 본문이 깨진 JSON 이면 예외가 아니라 Result 실패로 온다`() = runTest {
        // Given: 서버 앞단 프록시가 HTML 오류 페이지를 끼워 넣은 상황
        server.enqueue(jsonResponse(200, "<html>502 Bad Gateway</html>"))

        // When
        val result = memberRepository.getMyProfile()

        // Then: 파싱 예외가 ViewModel 까지 튀어 올라 앱을 죽이면 안 된다
        assertTrue(
            "실제: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is AppError.Unknown,
        )
    }

    @Test
    fun `서버에 닿지 못하면 네트워크 실패로 번역된다`() = runTest {
        // Given: 서버가 죽어 있다(지하철·비행기 모드와 같은 상황)
        server.shutdown()

        // When
        val result = memberRepository.getMyProfile()

        // Then
        assertTrue(
            "실제: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is AppError.Network,
        )
    }

    // ────────────────────────────────────────────────────────────────
    // 6. 로그아웃 — 서버가 실패해도 기기는 로그아웃된다
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `accessToken 이 만료돼 로그아웃이 401 로 실패해도 성공으로 끝난다`() = runTest {
        // Given: 로그아웃의 실체는 로컬 토큰 삭제이고, 이 401 은 매우 흔하다
        server.enqueue(jsonResponse(401, INVALID_TOKEN_ERROR_BODY))

        // When
        val result = authRepository.logout()

        // Then: 실패를 반환하면 사용자는 로그아웃 버튼을 눌러도 로그인 상태에 갇힌다
        assertTrue(result.isSuccess)
    }

    @Test
    fun `로그아웃이 401 로 실패해도 로컬 토큰은 지워진다`() = runTest {
        // Given
        server.enqueue(jsonResponse(401, INVALID_TOKEN_ERROR_BODY))

        // When
        authRepository.logout()

        // Then
        coVerify { tokenDataStore.clear() }
    }

    // ────────────────────────────────────────────────────────────────
    // 배관 헬퍼 / 실제 응답 본문 (계약 문서 §0.2·§0.3·§1-1·§1-2·§1-3)
    // ────────────────────────────────────────────────────────────────

    private fun retrofitBackedBy(store: TokenDataStore): Retrofit =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().addInterceptor(AuthInterceptor(store)).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)

    private companion object {

        const val LOGIN_SUCCESS_BODY = """
            {"status":200,"message":"로그인이 완료되었습니다.",
             "data":{"accessToken":"eyJhbGciOiJIUzI1NiJ9.access.signature"}}
        """

        const val MY_PROFILE_SUCCESS_BODY = """
            {"status":200,"message":"요청이 성공적으로 처리되었습니다.",
             "data":{"memberId":7,"email":"buyer@example.com","nickname":"동네주민",
                     "role":"ROLE_USER","status":"ACTIVE","createdAt":"2026-07-20T10:11:12.123456"}}
        """

        /** 백엔드가 나중에 필드를 추가한 상황(ignoreUnknownKeys 검증용). */
        const val MY_PROFILE_WITH_NEW_FIELDS_BODY = """
            {"status":200,"message":"요청이 성공적으로 처리되었습니다.","newField":1,
             "data":{"memberId":7,"email":"buyer@example.com","nickname":"동네주민",
                     "role":"ROLE_USER","status":"ACTIVE","createdAt":"2026-07-20T10:11:12.123456",
                     "profileImageUrl":"/api/products/images/abc.png","newField":1}}
        """

        const val LOCATIONS_SUCCESS_BODY = """
            {"status":200,"message":"요청이 성공적으로 처리되었습니다.",
             "data":[{"regionCode":"11680","regionName":"강남구","regionFullName":"서울특별시 강남구","sortOrder":0,"active":true},
                     {"regionCode":"11440","regionName":"마포구","regionFullName":"서울특별시 마포구","sortOrder":1,"active":false}]}
        """

        const val LOCATIONS_EMPTY_BODY = """
            {"status":200,"message":"요청이 성공적으로 처리되었습니다.","data":[]}
        """

        /** data 키가 통째로 사라진 성공 응답(logout·DELETE favorites·read 처리). */
        const val NO_DATA_SUCCESS_BODY = """
            {"status":200,"message":"로그아웃이 완료되었습니다."}
        """

        const val INVALID_PASSWORD_ERROR_BODY = """
            {"status":401,"error":"INVALID_PASSWORD","message":"비밀번호가 올바르지 않습니다.",
             "timestamp":"2026-07-26T13:45:30.123456"}
        """

        const val INVALID_TOKEN_ERROR_BODY = """
            {"status":401,"error":"INVALID_TOKEN","message":"유효하지 않은 토큰입니다.",
             "timestamp":"2026-07-26T13:45:30.123456"}
        """

        const val MEMBER_NOT_FOUND_ERROR_BODY = """
            {"status":404,"error":"MEMBER_NOT_FOUND","message":"존재하지 않는 회원입니다.",
             "timestamp":"2026-07-26T13:45:30.123456"}
        """

        const val TOO_MANY_ATTEMPTS_ERROR_BODY = """
            {"status":429,"error":"TOO_MANY_LOGIN_ATTEMPTS",
             "message":"로그인 시도가 너무 많습니다. 10분 후 다시 시도해 주세요.",
             "timestamp":"2026-07-26T13:45:30.123456"}
        """

        const val INTERNAL_SERVER_ERROR_BODY = """
            {"status":500,"error":"INTERNAL_SERVER_ERROR","message":"서버 내부 오류가 발생했습니다.",
             "timestamp":"2026-07-26T13:45:30.123456"}
        """

        /** 매핑 없는 URL 의 404 — 우리 ErrorResponse 가 아니라 스프링 부트 기본 바디다. */
        const val SPRING_DEFAULT_404_BODY = """
            {"timestamp":"2026-07-26T13:45:30.123+00:00","status":404,"error":"Not Found",
             "path":"/api/members/me"}
        """
    }
}
