package com.dongnemarket.chat;

import com.dongnemarket.category.entity.Category;
import com.dongnemarket.category.repository.CategoryRepository;
import com.dongnemarket.chat.entity.ChatRoom;
import com.dongnemarket.chat.repository.ChatMessageRepository;
import com.dongnemarket.chat.repository.ChatRoomRepository;
import com.dongnemarket.global.security.jwt.JwtTokenProvider;
import com.dongnemarket.member.entity.Member;
import com.dongnemarket.member.repository.MemberRepository;
import com.dongnemarket.product.entity.Product;
import com.dongnemarket.product.repository.ProductRepository;
import com.dongnemarket.region.entity.Region;
import com.dongnemarket.region.repository.RegionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 채팅 실시간(WebSocket/STOMP) 통합 테스트. 실제 서버(RANDOM_PORT) + 실제 STOMP 클라이언트로
 * CONNECT 인증 → SUBSCRIBE 인가 → REST 전송 시 broadcast(B안)를 end-to-end 검증한다.
 * <ul>
 *   <li>참여자가 방 토픽을 구독하면 상대의 REST 전송이 push로 수신된다(크라운 주얼).</li>
 *   <li>참여자가 읽으면 읽음 영수증이 방 서브토픽(.../read)으로 push된다.</li>
 *   <li>비참여자의 방 토픽·읽음 영수증 서브토픽 구독은 거부된다(도청 차단).</li>
 *   <li>토큰 없는 CONNECT는 거부된다.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("채팅 실시간(WebSocket) 통합 테스트")
class ChatWebSocketTest {

    private static final long TIMEOUT_SECONDS = 5;

    @LocalServerPort int port;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TestRestTemplate restTemplate;
    @Autowired MemberRepository memberRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductRepository productRepository;
    @Autowired RegionRepository regionRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatMessageRepository chatMessageRepository;

    private WebSocketStompClient stompClient;
    private Long categoryId;
    private Long roomId;
    private Member buyer;
    private Member seller;
    private Member outsider;
    private String buyerToken;
    private String sellerToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() {
        buyer = memberRepository.save(Member.createUser("ws-buyer@example.com", "encoded-pw", "wsbuyer"));
        seller = memberRepository.save(Member.createUser("ws-seller@example.com", "encoded-pw", "wsseller"));
        outsider = memberRepository.save(Member.createUser("ws-outsider@example.com", "encoded-pw", "wsoutsider"));
        Category category = categoryRepository.save(new Category("웹소켓테스트전용카테고리"));
        Region region = saveYeoksam();
        Product product = Product.create(seller, category, "맥북 프로", "상태 좋음",
                BigDecimal.valueOf(1_500_000), region);
        productRepository.save(product);
        ChatRoom room = chatRoomRepository.save(ChatRoom.of(product, buyer, seller));

        categoryId = category.getId();
        roomId = room.getId();
        buyerToken = jwtTokenProvider.createAccessToken(buyer.getId(), "ROLE_USER");
        sellerToken = jwtTokenProvider.createAccessToken(seller.getId(), "ROLE_USER");
        outsiderToken = jwtTokenProvider.createAccessToken(outsider.getId(), "ROLE_USER");

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(converter);
    }

    @AfterEach
    void cleanUp() {
        chatMessageRepository.deleteAll();
        chatRoomRepository.deleteAll();
        productRepository.deleteAll();
        memberRepository.deleteAll();
        categoryRepository.deleteById(categoryId);
        // 지역(region)은 RegionSeeder가 심은 공유 시드 마스터라 삭제하지 않는다(공유 H2를 다른 테스트와 공유).
        // saveYeoksam()이 findByCode-or-create 라 중복도 안 생긴다.
    }

    @Test
    @DisplayName("참여자가 방 토픽을 구독하면 상대의 REST 전송이 push로 수신된다")
    void participantReceivesBroadcastOnRestSend() throws Exception {
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        StompSession session = connect(buyerToken, new CountDownLatch(1));
        session.subscribe("/topic/chat-rooms/" + roomId, frameHandler(received));
        // SUBSCRIBE 프레임이 브로커에 등록될 시간을 준다(SimpleBroker는 미등록 시점의 메시지를 재전송하지 않음).
        Thread.sleep(500);

        sendMessageViaRest(sellerToken, roomId, "실시간으로 도착하나요?");

        Map<String, Object> payload = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(payload).isNotNull();
        assertThat(payload.get("content")).isEqualTo("실시간으로 도착하나요?");
        assertThat(((Number) payload.get("senderId")).longValue()).isEqualTo(seller.getId());
    }

    @Test
    @DisplayName("비참여자의 방 토픽 구독은 거부된다(도청 차단)")
    void outsiderSubscriptionIsRejected() throws Exception {
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        CountDownLatch errorLatch = new CountDownLatch(1);
        StompSession session = connect(outsiderToken, errorLatch);

        session.subscribe("/topic/chat-rooms/" + roomId, frameHandler(received));

        assertThat(errorLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("채팅 인터셉터는 비채팅 토픽(경매 등) 구독을 막지 않는다")
    void nonChatTopicSubscriptionPassesThrough() throws Exception {
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        CountDownLatch errorLatch = new CountDownLatch(1);
        StompSession session = connect(outsiderToken, errorLatch);

        // 채팅방 참여자가 아닌 사용자라도 채팅 외 목적지 구독은 ChatSubscribeInterceptor가 통과시켜야 한다.
        session.subscribe("/topic/auctions/1", frameHandler(received));

        assertThat(errorLatch.await(1, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    @DisplayName("상대가 읽으면 읽음 영수증이 방 서브토픽으로 push된다")
    void participantReceivesReadReceipt() throws Exception {
        // 읽을 대상(메시지)을 만든다. seller가 보낸 메시지를 buyer가 읽는 시나리오.
        sendMessageViaRest(sellerToken, roomId, "읽음 영수증 테스트");

        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        StompSession session = connect(sellerToken, new CountDownLatch(1));
        session.subscribe("/topic/chat-rooms/" + roomId + "/read", frameHandler(received));
        Thread.sleep(500);

        // buyer가 읽으면 → 발신자(seller)에게 읽음 영수증 push.
        readRoomViaRest(buyerToken, roomId);

        Map<String, Object> payload = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(payload).isNotNull();
        assertThat(((Number) payload.get("roomId")).longValue()).isEqualTo(roomId);
        assertThat(((Number) payload.get("readerId")).longValue()).isEqualTo(buyer.getId());
        assertThat(payload.get("lastReadMessageId")).isNotNull();
    }

    @Test
    @DisplayName("비참여자의 읽음 영수증 서브토픽 구독은 거부된다(도청 차단)")
    void outsiderReadReceiptSubscriptionIsRejected() throws Exception {
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        CountDownLatch errorLatch = new CountDownLatch(1);
        StompSession session = connect(outsiderToken, errorLatch);

        // 서브토픽(.../read)도 방 참여자만 구독 가능해야 한다(파싱이 첫 세그먼트만 보므로 인가가 이어짐).
        session.subscribe("/topic/chat-rooms/" + roomId + "/read", frameHandler(received));

        assertThat(errorLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("토큰 없는 CONNECT는 거부된다")
    void connectWithoutTokenIsRejected() {
        assertThatThrownBy(() -> connect(null, new CountDownLatch(1)))
                .isInstanceOf(Exception.class);
    }

    /** 인증 토큰(옵션)으로 CONNECT 한다. errorLatch는 서버 ERROR/전송오류 시 카운트다운된다. */
    private StompSession connect(String token, CountDownLatch errorLatch) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        return stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(@NonNull StompSession s, StompCommand command,
                                                @NonNull StompHeaders headers, @NonNull byte[] payload,
                                                @NonNull Throwable exception) {
                        errorLatch.countDown();
                    }

                    @Override
                    public void handleTransportError(@NonNull StompSession s, @NonNull Throwable exception) {
                        errorLatch.countDown();
                    }
                }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private StompSessionHandlerAdapter frameHandler(BlockingQueue<Map<String, Object>> sink) {
        return new StompSessionHandlerAdapter() {
            @Override
            @NonNull
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                sink.add((Map<String, Object>) payload);
            }
        };
    }

    private void sendMessageViaRest(String token, Long roomId, String content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        HttpEntity<String> request = new HttpEntity<>("{\"content\":\"" + content + "\"}", headers);
        restTemplate.postForEntity(
                "http://localhost:" + port + "/api/chat-rooms/" + roomId + "/messages",
                request, String.class);
    }

    private void readRoomViaRest(String token, Long roomId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        restTemplate.postForEntity(
                "http://localhost:" + port + "/api/chat-rooms/" + roomId + "/read",
                request, String.class);
    }

    private Region saveYeoksam() {
        Region seoul = regionRepository.findByCode("1100000000")
                .orElseGet(() -> regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")));
        Region gangnam = regionRepository.findByCode("1168000000")
                .orElseGet(() -> regionRepository.save(Region.child("1168000000", 2, seoul, "서울특별시 강남구", "강남구")));
        return regionRepository.findByCode("1168010100")
                .orElseGet(() -> regionRepository.save(Region.child("1168010100", 3, gangnam, "서울특별시 강남구 역삼동", "역삼동")));
    }
}
