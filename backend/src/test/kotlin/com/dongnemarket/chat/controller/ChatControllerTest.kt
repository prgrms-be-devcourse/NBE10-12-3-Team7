package com.dongnemarket.chat.controller

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.chat.entity.ChatMessage
import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatMessageRepository
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal

/**
 * 1:1 채팅 API 통합 테스트.
 *
 * 실제 HTTP 요청으로 사용자 유스케이스(성공·실패·엣지)를 검증한다.
 * 통신 계층만 MockMvc로 대체하고 Controller·Service·Repository는 실제로 동작한다(H2).
 * 실제 JWT로 @AuthenticationPrincipal(memberId) 바인딩·참여자 인가까지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("1:1 채팅 API 통합 테스트")
class ChatControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Autowired
    lateinit var chatRoomRepository: ChatRoomRepository

    @Autowired
    lateinit var chatMessageRepository: ChatMessageRepository

    private var categoryId = 0L
    private var productId = 0L
    private lateinit var buyer: Member
    private lateinit var seller: Member
    private lateinit var outsider: Member
    private lateinit var buyerToken: String
    private lateinit var sellerToken: String
    private lateinit var outsiderToken: String

    @BeforeEach
    fun setUp() {
        buyer = memberRepository.save(Member.createUser("buyer@example.com", "encoded-pw", "buyer"))
        seller = memberRepository.save(Member.createUser("seller@example.com", "encoded-pw", "seller"))
        outsider = memberRepository.save(Member.createUser("outsider@example.com", "encoded-pw", "outsider"))
        val category = categoryRepository.save(Category("채팅테스트전용카테고리"))
        val region = saveYeoksam()
        val product =
            Product.create(seller, category, "맥북 프로", "상태 좋음", BigDecimal.valueOf(1_500_000), region)
        product.changeThumbnailUrl("https://img.example/macbook.jpg")
        productRepository.save(product)

        categoryId = category.id!!
        productId = product.id!!
        buyerToken = bearer(buyer)
        sellerToken = bearer(seller)
        outsiderToken = bearer(outsider)
    }

    @AfterEach
    fun cleanUp() {
        chatMessageRepository.deleteAll()
        chatRoomRepository.deleteAll()
        productRepository.deleteAll()
        memberRepository.deleteAll()
        categoryRepository.deleteById(categoryId)
    }

    private fun bearer(member: Member): String = "Bearer " + jwtTokenProvider.createAccessToken(member.id!!, "ROLE_USER")

    private fun saveYeoksam(): Region {
        val seoul =
            regionRepository
                .findByCode("1100000000")
                .orElseGet { regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")) }
        val gangnam =
            regionRepository
                .findByCode("1168000000")
                .orElseGet { regionRepository.save(Region.child("1168000000", 2, seoul, "서울특별시 강남구", "강남구")) }
        return regionRepository
            .findByCode("1168010100")
            .orElseGet { regionRepository.save(Region.child("1168010100", 3, gangnam, "서울특별시 강남구 역삼동", "역삼동")) }
    }

    private fun saveRoom(
        buyerMember: Member,
        sellerMember: Member,
    ): ChatRoom {
        val product = productRepository.findById(productId).orElseThrow()
        return chatRoomRepository.save(ChatRoom.of(product, buyerMember, sellerMember))
    }

    /** buyer가 참여한 새 방을 별도 상품에 만든다. UNIQUE(product_id, buyer_id)라 방을 여러 개 만들려면 상품이 달라야 한다. */
    private fun saveRoomOnNewProduct(): ChatRoom {
        val category = categoryRepository.findById(categoryId).orElseThrow()
        val region = saveYeoksam()
        val product = Product.create(seller, category, "상품", "설명", BigDecimal.valueOf(10_000), region)
        productRepository.save(product)
        return chatRoomRepository.save(ChatRoom.of(product, buyer, seller))
    }

    /** buyer가 '판매자' 좌석인 방(상대는 outsider). 안읽음 CASE의 판매자 분기를 태우기 위함. */
    private fun saveRoomWhereBuyerIsSeller(): ChatRoom {
        val category = categoryRepository.findById(categoryId).orElseThrow()
        val region = saveYeoksam()
        val product = Product.create(buyer, category, "내가 파는 상품", "설명", BigDecimal.valueOf(5_000), region)
        productRepository.save(product)
        return chatRoomRepository.save(ChatRoom.of(product, outsider, buyer))
    }

    @Nested
    @DisplayName("채팅방 연결 (POST /api/chat-rooms)")
    inner class CreateRoom {
        @Test
        fun `처음 연결하면 방을 생성하고 상품 상세·판매자와 함께 200으로 반환한다`() {
            mockMvc
                .perform(
                    post("/api/chat-rooms")
                        .header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":$productId}"),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").isNumber())
                .andExpect(jsonPath("$.data.product.productId").value(productId))
                .andExpect(jsonPath("$.data.product.title").value("맥북 프로"))
                .andExpect(jsonPath("$.data.product.description").value("상태 좋음"))
                .andExpect(jsonPath("$.data.product.region").doesNotExist())
                .andExpect(jsonPath("$.data.product.regionCode").value("1168010100"))
                .andExpect(jsonPath("$.data.product.regionName").value("역삼동"))
                .andExpect(jsonPath("$.data.product.regionFullName").value("서울특별시 강남구 역삼동"))
                .andExpect(jsonPath("$.data.product.thumbnailUrl").value("https://img.example/macbook.jpg"))
                .andExpect(jsonPath("$.data.seller.nickname").value("seller"))
                .andExpect(jsonPath("$.data.seller.withdrawn").value(false))
        }

        @Test
        fun `이미 방이 있으면 같은 방을 200으로 반환한다(get-or-create 멱등)`() {
            val firstRoomId = extractRoomId(connectAsBuyer())
            val secondRoomId = extractRoomId(connectAsBuyer())

            assertThat(secondRoomId).isEqualTo(firstRoomId)
        }

        @Test
        fun `자신의 상품에는 채팅을 시작할 수 없다(400 CANNOT_CHAT_WITH_SELF)`() {
            mockMvc
                .perform(
                    post("/api/chat-rooms")
                        .header("Authorization", sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":$productId}"),
                ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CANNOT_CHAT_WITH_SELF"))
        }

        @Test
        fun `존재하지 않는 상품이면 404 PRODUCT_NOT_FOUND`() {
            mockMvc
                .perform(
                    post("/api/chat-rooms")
                        .header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":999999}"),
                ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
        }

        @Test
        fun `토큰이 없으면 401`() {
            mockMvc
                .perform(
                    post("/api/chat-rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":$productId}"),
                ).andExpect(status().isUnauthorized())
        }

        private fun connectAsBuyer(): String =
            mockMvc
                .perform(
                    post("/api/chat-rooms")
                        .header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":$productId}"),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString

        private fun extractRoomId(body: String): Long = JsonPath.parse(body).read("$.data.roomId", Long::class.java)
    }

    @Nested
    @DisplayName("내 채팅방 목록 (GET /api/chat-rooms)")
    inner class GetMyRooms {
        @Test
        fun `참여한 방을 상품요약·상대닉네임·마지막 메시지와 함께 반환한다`() {
            val room = saveRoom(buyer, seller)
            chatMessageRepository.save(ChatMessage.of(room, buyer, "안녕하세요"))
            chatMessageRepository.save(ChatMessage.of(room, seller, "네 안녕하세요"))

            mockMvc
                .perform(get("/api/chat-rooms").header("Authorization", buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomId").value(room.id))
                .andExpect(jsonPath("$.data[0].product.title").value("맥북 프로"))
                .andExpect(jsonPath("$.data[0].opponent.nickname").value("seller"))
                .andExpect(jsonPath("$.data[0].lastMessage.content").value("네 안녕하세요"))
                // 아직 읽지 않았으므로 상대(seller)가 보낸 1건이 안읽음(내가 보낸 건 제외)
                .andExpect(jsonPath("$.data[0].unreadCount").value(1))
        }

        @Test
        fun `안읽음 수는 좌석(구매자·판매자)에 맞는 읽음 지점으로 세고, 내가 보낸 메시지는 제외한다`() {
            // roomA: buyer가 '구매자' 좌석. seller 2건 + buyer 1건(내 메시지) → 안읽음 2
            val roomA = saveRoom(buyer, seller)
            chatMessageRepository.save(ChatMessage.of(roomA, seller, "a1"))
            chatMessageRepository.save(ChatMessage.of(roomA, buyer, "내가 보낸 것"))
            chatMessageRepository.save(ChatMessage.of(roomA, seller, "a2"))
            // roomB: buyer가 '판매자' 좌석. 상대(outsider) 1건 → 안읽음 1 (판매자 분기 검증)
            val roomB = saveRoomWhereBuyerIsSeller()
            chatMessageRepository.save(ChatMessage.of(roomB, outsider, "b1"))

            // 활동 시각 DESC 정렬: roomB의 b1이 가장 최근 → [roomB, roomA]
            mockMvc
                .perform(get("/api/chat-rooms").header("Authorization", buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].roomId").value(roomB.id))
                .andExpect(jsonPath("$.data[0].unreadCount").value(1))
                .andExpect(jsonPath("$.data[1].roomId").value(roomA.id))
                .andExpect(jsonPath("$.data[1].unreadCount").value(2))
        }

        @Test
        fun `판매자 입장에서는 상대방이 구매자로 표시된다`() {
            saveRoom(buyer, seller)

            mockMvc
                .perform(get("/api/chat-rooms").header("Authorization", sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].opponent.nickname").value("buyer"))
        }

        @Test
        fun `탈퇴한 상대는 닉네임이 '탈퇴한 사용자'로 마스킹되고 withdrawn=true로 표시된다`() {
            saveRoom(buyer, seller)
            seller.softDelete() // 상대(판매자) 탈퇴
            memberRepository.save(seller)

            mockMvc
                .perform(get("/api/chat-rooms").header("Authorization", buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].opponent.nickname").value("탈퇴한 사용자"))
                // 프론트가 입력창 비활성화·안내 배너를 띄우는 신뢰 신호
                .andExpect(jsonPath("$.data[0].opponent.withdrawn").value(true))
        }

        @Test
        fun `참여하지 않은 사용자에게는 방이 보이지 않는다`() {
            saveRoom(buyer, seller)

            mockMvc
                .perform(get("/api/chat-rooms").header("Authorization", outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
        }

        @Test
        fun `메시지가 없는 방은 lastMessage가 null이다`() {
            saveRoom(buyer, seller)

            mockMvc
                .perform(get("/api/chat-rooms").header("Authorization", buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lastMessage").doesNotExist())
        }

        @Test
        fun `마지막 메시지가 최근인 방이 먼저 오고, 메시지 없는 방은 방 생성 시각 기준으로 정렬된다`() {
            // 생성 순서(=id 순): empty < older < newer. 메시지는 older, newer 순으로 저장돼 newer가 더 최근.
            val empty = saveRoomOnNewProduct() // 메시지 없음 → 활동 시각 = 방 생성 시각(가장 이름)
            val older = saveRoomOnNewProduct() // 오래된 마지막 메시지
            val newer = saveRoomOnNewProduct() // 최근 마지막 메시지
            chatMessageRepository.save(ChatMessage.of(older, buyer, "예전 대화"))
            chatMessageRepository.save(ChatMessage.of(newer, buyer, "최근 대화"))

            mockMvc
                .perform(get("/api/chat-rooms").header("Authorization", buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].roomId").value(newer.id))
                .andExpect(jsonPath("$.data[1].roomId").value(older.id))
                .andExpect(jsonPath("$.data[2].roomId").value(empty.id))
        }
    }

    @Nested
    @DisplayName("메시지 조회 (GET /api/chat-rooms/{roomId}/messages)")
    inner class GetMessages {
        @Test
        fun `최신순으로 반환하고 커서로 다음 페이지를 이어 조회한다`() {
            val room = saveRoom(buyer, seller)
            val m1 = chatMessageRepository.save(ChatMessage.of(room, buyer, "첫번째"))
            val m2 = chatMessageRepository.save(ChatMessage.of(room, seller, "두번째"))
            chatMessageRepository.save(ChatMessage.of(room, buyer, "세번째"))

            // 첫 페이지: 최신 2개 [세번째, 두번째], hasNext true, nextCursor = 두번째 id
            val firstPage =
                mockMvc
                    .perform(
                        get("/api/chat-rooms/{roomId}/messages", room.id)
                            .header("Authorization", buyerToken)
                            .param("size", "2"),
                    ).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.messages.length()").value(2))
                    .andExpect(jsonPath("$.data.messages[0].content").value("세번째"))
                    .andExpect(jsonPath("$.data.messages[1].content").value("두번째"))
                    .andExpect(jsonPath("$.data.hasNext").value(true))
                    .andExpect(jsonPath("$.data.nextCursor").value(m2.id))
                    .andReturn()
                    .response.contentAsString

            val nextCursor: Long = JsonPath.parse(firstPage).read("$.data.nextCursor", Long::class.java)

            // 다음 페이지: [첫번째], hasNext false
            mockMvc
                .perform(
                    get("/api/chat-rooms/{roomId}/messages", room.id)
                        .header("Authorization", buyerToken)
                        .param("size", "2")
                        .param("cursor", nextCursor.toString()),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(1))
                .andExpect(jsonPath("$.data.messages[0].content").value("첫번째"))
                .andExpect(jsonPath("$.data.messages[0].messageId").value(m1.id))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
        }

        @Test
        fun `참여자가 아니면 403 CHAT_ACCESS_DENIED`() {
            val room = saveRoom(buyer, seller)

            mockMvc
                .perform(
                    get("/api/chat-rooms/{roomId}/messages", room.id)
                        .header("Authorization", outsiderToken),
                ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CHAT_ACCESS_DENIED"))
        }

        @Test
        fun `존재하지 않는 방이면 404 CHAT_ROOM_NOT_FOUND`() {
            mockMvc
                .perform(
                    get("/api/chat-rooms/{roomId}/messages", 999999)
                        .header("Authorization", buyerToken),
                ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CHAT_ROOM_NOT_FOUND"))
        }
    }

    @Nested
    @DisplayName("메시지 전송 (POST /api/chat-rooms/{roomId}/messages)")
    inner class SendMessage {
        @Test
        fun `참여자가 메시지를 보내면 201로 저장된 메시지를 반환한다`() {
            val room = saveRoom(buyer, seller)

            mockMvc
                .perform(
                    post("/api/chat-rooms/{roomId}/messages", room.id)
                        .header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"안녕하세요\"}"),
                ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.messageId").isNumber())
                .andExpect(jsonPath("$.data.senderId").value(buyer.id))
                .andExpect(jsonPath("$.data.content").value("안녕하세요"))
        }

        @Test
        fun `참여자가 아니면 403 CHAT_ACCESS_DENIED`() {
            val room = saveRoom(buyer, seller)

            mockMvc
                .perform(
                    post("/api/chat-rooms/{roomId}/messages", room.id)
                        .header("Authorization", outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"끼어들기\"}"),
                ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CHAT_ACCESS_DENIED"))
        }

        @Test
        fun `내용이 공백이면 400`() {
            val room = saveRoom(buyer, seller)

            mockMvc
                .perform(
                    post("/api/chat-rooms/{roomId}/messages", room.id)
                        .header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"),
                ).andExpect(status().isBadRequest())
        }

        @Test
        fun `내용이 1000자를 초과하면 400`() {
            val room = saveRoom(buyer, seller)
            val tooLong = "a".repeat(1001)

            mockMvc
                .perform(
                    post("/api/chat-rooms/{roomId}/messages", room.id)
                        .header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"$tooLong\"}"),
                ).andExpect(status().isBadRequest())
        }

        @Test
        fun `존재하지 않는 방이면 404 CHAT_ROOM_NOT_FOUND`() {
            mockMvc
                .perform(
                    post("/api/chat-rooms/{roomId}/messages", 999999)
                        .header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"안녕\"}"),
                ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CHAT_ROOM_NOT_FOUND"))
        }

        @Test
        fun `판매자가 탈퇴하면 구매자는 400 CHAT_PARTNER_WITHDRAWN으로 전송할 수 없다`() {
            val room = saveRoom(buyer, seller)
            seller.softDelete()
            memberRepository.save(seller)

            mockMvc
                .perform(
                    post("/api/chat-rooms/{roomId}/messages", room.id)
                        .header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"계세요?\"}"),
                ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CHAT_PARTNER_WITHDRAWN"))
        }

        @Test
        fun `구매자가 탈퇴하면 판매자도 400 CHAT_PARTNER_WITHDRAWN으로 전송할 수 없다(양방향)`() {
            val room = saveRoom(buyer, seller)
            buyer.softDelete()
            memberRepository.save(buyer)

            mockMvc
                .perform(
                    post("/api/chat-rooms/{roomId}/messages", room.id)
                        .header("Authorization", sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"거래 하실래요?\"}"),
                ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CHAT_PARTNER_WITHDRAWN"))
        }

        @Test
        fun `상대가 탈퇴해도 대화 히스토리 조회·방 목록(읽기)은 200으로 유지된다`() {
            val room = saveRoom(buyer, seller)
            chatMessageRepository.save(ChatMessage.of(room, buyer, "예전 대화"))
            seller.softDelete()
            memberRepository.save(seller)

            mockMvc
                .perform(
                    get("/api/chat-rooms/{roomId}/messages", room.id)
                        .header("Authorization", buyerToken),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(1))

            mockMvc
                .perform(get("/api/chat-rooms").header("Authorization", buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
        }
    }

    @Nested
    @DisplayName("읽음 처리 (POST /api/chat-rooms/{roomId}/read)")
    inner class MarkAsRead {
        @Test
        fun `읽음 처리하면 그 방의 안읽음이 0이 되고, 다른 방은 영향받지 않는다`() {
            val target = saveRoom(buyer, seller)
            chatMessageRepository.save(ChatMessage.of(target, seller, "t1"))
            chatMessageRepository.save(ChatMessage.of(target, seller, "t2"))
            val other = saveRoomOnNewProduct()
            chatMessageRepository.save(ChatMessage.of(other, seller, "o1"))

            // 읽음 처리 전: target 안읽음 2
            mockMvc
                .perform(
                    post("/api/chat-rooms/{roomId}/read", target.id)
                        .header("Authorization", buyerToken),
                ).andExpect(status().isOk())

            // 활동 시각 DESC: other의 o1이 target의 t2보다 나중 → [other, target]
            mockMvc
                .perform(get("/api/chat-rooms").header("Authorization", buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].roomId").value(other.id))
                .andExpect(jsonPath("$.data[0].unreadCount").value(1))
                .andExpect(jsonPath("$.data[1].roomId").value(target.id))
                .andExpect(jsonPath("$.data[1].unreadCount").value(0))
        }

        @Test
        fun `참여자가 아니면 403 CHAT_ACCESS_DENIED`() {
            val room = saveRoom(buyer, seller)

            mockMvc
                .perform(
                    post("/api/chat-rooms/{roomId}/read", room.id)
                        .header("Authorization", outsiderToken),
                ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CHAT_ACCESS_DENIED"))
        }

        @Test
        fun `존재하지 않는 방이면 404 CHAT_ROOM_NOT_FOUND`() {
            mockMvc
                .perform(
                    post("/api/chat-rooms/{roomId}/read", 999999)
                        .header("Authorization", buyerToken),
                ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CHAT_ROOM_NOT_FOUND"))
        }
    }
}
