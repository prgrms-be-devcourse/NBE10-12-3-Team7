package com.dongnemarket.chat

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.math.BigDecimal
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 채팅 실시간(WebSocket/STOMP) 통합 테스트. 실제 서버(RANDOM_PORT) + 실제 STOMP 클라이언트로
 * CONNECT 인증 → SUBSCRIBE 인가 → REST 전송 시 broadcast(B안)를 end-to-end 검증한다.
 * - 참여자가 방 토픽을 구독하면 상대의 REST 전송이 push로 수신된다(크라운 주얼).
 * - 참여자가 읽으면 읽음 영수증이 방 서브토픽(.../read)으로 push된다.
 * - 비참여자의 방 토픽·읽음 영수증 서브토픽 구독은 거부된다(도청 차단).
 * - 메시지를 받으면 수신자의 개인 큐(/user/queue/notifications)로 안읽음 배지 신호가 push된다.
 * - 토큰 없는 CONNECT는 거부된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("채팅 실시간(WebSocket) 통합 테스트")
class ChatWebSocketTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var restTemplate: TestRestTemplate

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

    private lateinit var stompClient: WebSocketStompClient
    private var categoryId = 0L
    private var roomId = 0L
    private lateinit var buyer: Member
    private lateinit var seller: Member
    private lateinit var outsider: Member
    private lateinit var buyerToken: String
    private lateinit var sellerToken: String
    private lateinit var outsiderToken: String

    @BeforeEach
    fun setUp() {
        buyer = memberRepository.save(Member.createUser("ws-buyer@example.com", "encoded-pw", "wsbuyer"))
        seller = memberRepository.save(Member.createUser("ws-seller@example.com", "encoded-pw", "wsseller"))
        outsider = memberRepository.save(Member.createUser("ws-outsider@example.com", "encoded-pw", "wsoutsider"))
        val category = categoryRepository.save(Category("웹소켓테스트전용카테고리"))
        val region = saveYeoksam()
        val product =
            Product.create(seller, category, "맥북 프로", "상태 좋음", BigDecimal.valueOf(1_500_000), region)
        productRepository.save(product)
        val room = chatRoomRepository.save(ChatRoom.of(product, buyer, seller))

        categoryId = category.id!!
        roomId = room.id!!
        buyerToken = jwtTokenProvider.createAccessToken(buyer.id!!, "ROLE_USER")
        sellerToken = jwtTokenProvider.createAccessToken(seller.id!!, "ROLE_USER")
        outsiderToken = jwtTokenProvider.createAccessToken(outsider.id!!, "ROLE_USER")

        val objectMapper = ObjectMapper().registerModule(JavaTimeModule())
        val converter = MappingJackson2MessageConverter()
        converter.objectMapper = objectMapper
        stompClient = WebSocketStompClient(StandardWebSocketClient())
        stompClient.messageConverter = converter
    }

    @AfterEach
    fun cleanUp() {
        chatMessageRepository.deleteAll()
        chatRoomRepository.deleteAll()
        productRepository.deleteAll()
        memberRepository.deleteAll()
        categoryRepository.deleteById(categoryId)
        // 지역(region)은 RegionSeeder가 심은 공유 시드 마스터라 삭제하지 않는다(공유 H2를 다른 테스트와 공유).
        // saveYeoksam()이 findByCode-or-create 라 중복도 안 생긴다.
    }

    @Test
    @DisplayName("참여자가 방 토픽을 구독하면 상대의 REST 전송이 push로 수신된다")
    fun participantReceivesBroadcastOnRestSend() {
        val received: BlockingQueue<Map<String, Any>> = LinkedBlockingQueue()
        val session = connect(buyerToken, CountDownLatch(1))
        session.subscribe("/topic/chat-rooms/$roomId", frameHandler(received))
        // SUBSCRIBE 프레임이 브로커에 등록될 시간을 준다(SimpleBroker는 미등록 시점의 메시지를 재전송하지 않음).
        Thread.sleep(500)

        sendMessageViaRest(sellerToken, roomId, "실시간으로 도착하나요?")

        val payload = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertThat(payload).isNotNull()
        assertThat(payload!!["content"]).isEqualTo("실시간으로 도착하나요?")
        assertThat((payload["senderId"] as Number).toLong()).isEqualTo(seller.id!!)
    }

    @Test
    @DisplayName("비참여자의 방 토픽 구독은 거부된다(도청 차단)")
    fun outsiderSubscriptionIsRejected() {
        val received: BlockingQueue<Map<String, Any>> = LinkedBlockingQueue()
        val errorLatch = CountDownLatch(1)
        val session = connect(outsiderToken, errorLatch)

        session.subscribe("/topic/chat-rooms/$roomId", frameHandler(received))

        assertThat(errorLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(received).isEmpty()
    }

    @Test
    @DisplayName("채팅 인터셉터는 비채팅 토픽(경매 등) 구독을 막지 않는다")
    fun nonChatTopicSubscriptionPassesThrough() {
        val received: BlockingQueue<Map<String, Any>> = LinkedBlockingQueue()
        val errorLatch = CountDownLatch(1)
        val session = connect(outsiderToken, errorLatch)

        // 채팅방 참여자가 아닌 사용자라도 채팅 외 목적지 구독은 ChatSubscribeInterceptor가 통과시켜야 한다.
        session.subscribe("/topic/auctions/1", frameHandler(received))

        assertThat(errorLatch.await(1, TimeUnit.SECONDS)).isFalse()
    }

    @Test
    @DisplayName("상대가 읽으면 읽음 영수증이 방 서브토픽으로 push된다")
    fun participantReceivesReadReceipt() {
        // 읽을 대상(메시지)을 만든다. seller가 보낸 메시지를 buyer가 읽는 시나리오.
        sendMessageViaRest(sellerToken, roomId, "읽음 영수증 테스트")

        val received: BlockingQueue<Map<String, Any>> = LinkedBlockingQueue()
        val session = connect(sellerToken, CountDownLatch(1))
        session.subscribe("/topic/chat-rooms/$roomId/read", frameHandler(received))
        Thread.sleep(500)

        // buyer가 읽으면 → 발신자(seller)에게 읽음 영수증 push.
        readRoomViaRest(buyerToken, roomId)

        val payload = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertThat(payload).isNotNull()
        assertThat((payload!!["roomId"] as Number).toLong()).isEqualTo(roomId)
        assertThat((payload["readerId"] as Number).toLong()).isEqualTo(buyer.id!!)
        assertThat(payload["lastReadMessageId"]).isNotNull()
    }

    @Test
    @DisplayName("비참여자의 읽음 영수증 서브토픽 구독은 거부된다(도청 차단)")
    fun outsiderReadReceiptSubscriptionIsRejected() {
        val received: BlockingQueue<Map<String, Any>> = LinkedBlockingQueue()
        val errorLatch = CountDownLatch(1)
        val session = connect(outsiderToken, errorLatch)

        // 서브토픽(.../read)도 방 참여자만 구독 가능해야 한다(파싱이 첫 세그먼트만 보므로 인가가 이어짐).
        session.subscribe("/topic/chat-rooms/$roomId/read", frameHandler(received))

        assertThat(errorLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(received).isEmpty()
    }

    @Test
    @DisplayName("메시지를 받으면 수신자의 개인 큐로 안읽음 배지 신호가 push된다")
    fun recipientReceivesUnreadBadgeSignal() {
        val received: BlockingQueue<Map<String, Any>> = LinkedBlockingQueue()
        // 수신자(buyer)는 방과 무관하게 자기 개인 큐 하나만 구독한다.
        val session = connect(buyerToken, CountDownLatch(1))
        session.subscribe("/user/queue/notifications", frameHandler(received))
        Thread.sleep(500)

        // seller가 메시지를 보내면 → 커밋 후(AFTER_COMMIT) ChatMessageSentEvent → 수신자(buyer) 배지 신호 push.
        sendMessageViaRest(sellerToken, roomId, "배지 신호 테스트")

        val payload = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // user destination(/user/queue) 전달 검증 — 브로커에 /queue 미등록 시 조용히 유실됨(WebSocketConfig).
        assertThat(payload).isNotNull()
        assertThat(payload!!["type"]).isEqualTo("UNREAD_CHANGED")
    }

    @Test
    @DisplayName("토큰 없는 CONNECT는 거부된다")
    fun connectWithoutTokenIsRejected() {
        assertThatThrownBy { connect(null, CountDownLatch(1)) }
            .isInstanceOf(Exception::class.java)
    }

    /** 인증 토큰(옵션)으로 CONNECT 한다. errorLatch는 서버 ERROR/전송오류 시 카운트다운된다. */
    private fun connect(
        token: String?,
        errorLatch: CountDownLatch,
    ): StompSession {
        val connectHeaders = StompHeaders()
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer $token")
        }
        return stompClient
            .connectAsync(
                "ws://localhost:$port/ws",
                WebSocketHttpHeaders(),
                connectHeaders,
                object : StompSessionHandlerAdapter() {
                    override fun handleException(
                        session: StompSession,
                        command: StompCommand?,
                        headers: StompHeaders,
                        payload: ByteArray,
                        exception: Throwable,
                    ) {
                        errorLatch.countDown()
                    }

                    override fun handleTransportError(
                        session: StompSession,
                        exception: Throwable,
                    ) {
                        errorLatch.countDown()
                    }
                },
            ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun frameHandler(sink: BlockingQueue<Map<String, Any>>): StompSessionHandlerAdapter =
        object : StompSessionHandlerAdapter() {
            override fun getPayloadType(headers: StompHeaders): Type = Map::class.java

            @Suppress("UNCHECKED_CAST")
            override fun handleFrame(
                headers: StompHeaders,
                payload: Any?,
            ) {
                sink.add(payload as Map<String, Any>)
            }
        }

    private fun sendMessageViaRest(
        token: String,
        roomId: Long,
        content: String,
    ) {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.setBearerAuth(token)
        val request = HttpEntity("{\"content\":\"$content\"}", headers)
        restTemplate.postForEntity(
            "http://localhost:$port/api/chat-rooms/$roomId/messages",
            request,
            String::class.java,
        )
    }

    private fun readRoomViaRest(
        token: String,
        roomId: Long,
    ) {
        val headers = HttpHeaders()
        headers.setBearerAuth(token)
        val request = HttpEntity<Void>(headers)
        restTemplate.postForEntity(
            "http://localhost:$port/api/chat-rooms/$roomId/read",
            request,
            String::class.java,
        )
    }

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

    companion object {
        private const val TIMEOUT_SECONDS = 5L
    }
}
