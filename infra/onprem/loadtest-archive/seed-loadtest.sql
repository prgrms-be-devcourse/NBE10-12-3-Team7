-- ============================================================================
-- 부하테스트 통합 시드 (MySQL 8, dongne_market) — 5개 도메인 공용
--   Product / Report / Comment·Notification 볼륨을 한 번에 만든다.
--   Member(/me)·Admin AI 는 대량 데이터가 불필요(아래 "볼륨은 도메인별" 표 참고).
--
-- 실행 (infra/onprem/loadtest 에서):
--   Get-Content seed-loadtest.sql | docker exec -i dongne-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" dongne_market'
--
-- ⚠️ 처음엔 @product_rows 를 10000 으로 두고 파이프라인·각 테스트 경로를 검증한 뒤 100000 으로 올릴 것.
-- 정리: 맨 아래 "CLEANUP" 블록.
-- ============================================================================

-- ── 파라미터 ────────────────────────────────────────────────────────────────
SET @product_rows = 100000;  -- 상품 건수. (현재 CROSS JOIN 5단 = 최대 100000) 드라이런은 10000 권장.
SET @report_rows  = 40;      -- 신고자 1명의 신고 내역 건수(수십 건이면 N+1 차이가 보임)
SET @comment_rows = 3000;    -- 한 상품에 달 댓글 수(댓글 목록 조회 부하)
SET @noti_rows    = 3000;    -- 안읽은 알림 수(unread-count 카운트 부하)

SET @favorite_rows = 200;    -- 내 찜 목록 건수(서버 상한 Limit.of(200)에 맞춰 목록을 가득 채움)
SET @chat_rooms    = 60;     -- read-load 계정이 buyer 로 참여한 채팅방 수(unread-count 의 getMyRooms 무거운 경로용)
SET @chat_msgs     = 10;     -- 방당 메시지 수(홀수 n=상대(seller)발신=안읽음, 짝수 n=본인발신)

SET @reporter_id   = 2;      -- report-list-n1 / report-duplicate 를 돌릴 계정의 members.id
SET @comment_pid   = 1;      -- 댓글을 몰아넣을 상품(read-load.js 의 PRODUCT_ID 와 일치)
SET @comment_author = 1;     -- 댓글 작성자(상품 소유자와 달라야 자기댓글 예외 안 남)
SET @noti_recipient = 2;     -- 알림 수신자 = read-load.js 로그인 계정
-- ⚠️ @favorite_owner·@chat_buyer 는 read-load.js 로그인 계정과 반드시 같아야 목록이 비지 않는다(= @noti_recipient).
SET @favorite_owner = 2;     -- 찜 소유자 = read-load.js 로그인 계정
SET @chat_buyer     = 2;     -- 채팅 buyer = read-load.js 로그인 계정

SET SESSION cte_max_recursion_depth = 10000;   -- 댓글/알림 3000 재귀용(상품은 재귀 안 씀)

-- ── (0) 전용 판매자 1명 — 시드 상품 전부의 소유자 ────────────────────────────
--   • status=ACTIVE 라야 목록/검색/카테고리 쿼리의 m.status='ACTIVE' 필터를 통과한다.
--   • 소유자를 신고자·부하계정과 분리 → 자기상품 신고/찜/댓글 차단(CANNOT_REPORT_OWN_PRODUCT 등)을 피한다.
--   • password 는 기존 회원의 유효한 BCrypt 해시를 재사용(로그인은 안 하지만 NOT NULL 이라 채운다).
INSERT INTO members (created_at, updated_at, nickname, email, password, role, status)
SELECT NOW(6), NOW(6), 'loadtest-seller', 'loadtest-seller@dongne.test',
       (SELECT password FROM (SELECT password FROM members WHERE id = 1) AS h),
       'ROLE_USER', 'ACTIVE'
FROM (SELECT 1) AS d
WHERE NOT EXISTS (SELECT 1 FROM members WHERE email = 'loadtest-seller@dongne.test');

SET @seller_id = (SELECT id FROM members WHERE email = 'loadtest-seller@dongne.test');

-- ── (1) 상품 @product_rows 건 (Product 도메인 4개 테스트 공용) ────────────────
--   상태 혼합: 삭제 20% + 숨김 10% + 거래완료 10% = 약 40% 를 조회에서 걸러지게 만든다.
--   예약: rn<=5 (→ product.id 2~6) 은 무조건 정상(ON_SALE·숨김X·삭제X). product.id 1 은 기존 정상 →
--         product_detail_hotrow 의 HOT_IDS=[1..5] 가 전부 정상 상품이 된다.
--   지역 편중: 20% 를 한 지역에 몰고 나머지는 분산(균등분포는 실사용과 다름).
--   검색어: title·description 에 키워드를 심어 검색이 빈 결과가 아니라 실제 풀스캔을 타게 한다.
INSERT INTO products
  (favorite_count, hidden, price, category_id, created_at, deleted_at,
   member_id, view_count, region, title, description, thumbnail_url, trade_status)
WITH d(n) AS (
  SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
  UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
)
SELECT
  0,
  CASE WHEN rn > 5 AND rn % 10 = 2 THEN b'1' ELSE b'0' END,                       -- 숨김 10%
  1000 + (rn % 200) * 500,
  1 + (rn % 8),                                                                    -- 카테고리 1~8
  NOW(6) - INTERVAL rn SECOND,
  CASE WHEN rn > 5 AND rn % 10 IN (0, 1) THEN NOW(6) - INTERVAL rn SECOND END,     -- 삭제 20%
  @seller_id,
  0,
  CASE WHEN rn % 5 = 0 THEN '서울특별시 강남구'                                     -- 편중 20%
       ELSE ELT(1 + (rn % 6), '서울특별시 마포구','부산광역시 해운대구','인천광역시 연수구',
                              '대구광역시 수성구','경기도 성남시','광주광역시 서구') END,
  CONCAT(ELT(1 + (rn % 12), '의자','책상','노트북','자전거','카메라','냉장고',
                            '소파','운동화','가방','모니터','침대','에어컨'),
         ' 부하테스트 ', rn, '번'),
  CONCAT('부하테스트 더미 상품 설명 ', rn, ' — ',
         ELT(1 + (rn % 12), '의자','책상','노트북','자전거','카메라','냉장고',
                            '소파','운동화','가방','모니터','침대','에어컨'), ' 상태 양호'),
  CONCAT('https://dummy.loadtest.local/thumb/', rn, '.jpg'),                       -- thumbnail_url(목록 응답용)
  CASE WHEN rn > 5 AND rn % 10 = 3 THEN 'COMPLETED'                                -- 거래완료 10%
       WHEN rn % 7 = 0 THEN 'RESERVED'
       ELSE 'ON_SALE' END
FROM (
  SELECT ROW_NUMBER() OVER () AS rn
  FROM d a CROSS JOIN d b CROSS JOIN d c CROSS JOIN d e CROSS JOIN d f            -- 최대 100000
) nums
WHERE rn <= @product_rows;

-- ── (2) 신고 내역 — 신고자 1명에게 수십 건 (report-list-n1 의 N+1/EntityGraph 검증) ──
--   유니크 (reporter_id, target_product_id): 대상 상품이 전부 달라야 하므로 seller 상품에서 서로 다른 id 를 뽑는다.
--   seller 소유라 신고자(≠seller)의 자기상품 신고 차단에 안 걸린다.
INSERT INTO reports
  (created_at, updated_at, reporter_id, target_member_id, target_product_id,
   content, evidence_image_url, reason, report_type, status)
SELECT NOW(6), NOW(6), @reporter_id, NULL, p.id,
       CONCAT('부하테스트 신고 상품#', p.id), NULL,
       ELT(1 + (p.r % 5), 'ETC','FAKE_ITEM','FRAUD_SUSPECTED','INAPPROPRIATE_CONTENT','PROHIBITED_ITEM'),
       'PRODUCT', 'RECEIVED'
FROM (
  SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS r
  FROM products WHERE member_id = @seller_id AND deleted_at IS NULL
) p
WHERE p.r <= @report_rows
  AND NOT EXISTS (SELECT 1 FROM reports r2
                  WHERE r2.reporter_id = @reporter_id AND r2.target_product_id = p.id);

-- ── (3) 댓글 @comment_rows 건 → GET /api/products/{id}/comments (전체 조회 + member fetch join) ──
INSERT INTO comments (content, member_id, product_id, created_at, updated_at, deleted_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @comment_rows)
SELECT CONCAT('부하테스트 더미 댓글 #', n), @comment_author, @comment_pid,
       NOW(6) - INTERVAL n SECOND, NOW(6) - INTERVAL n SECOND, NULL
FROM seq;

-- ── (4) 안읽은 알림 @noti_rows 건 → GET /api/notifications/unread-count ──────────
INSERT INTO notifications (is_read, message, type, product_id, recipient_id, last_notified_at, created_at, updated_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @noti_rows)
SELECT b'0', CONCAT('💬 부하테스트 더미 알림 #', n), 'COMMENT', @comment_pid, @noti_recipient,
       NOW(6) - INTERVAL n SECOND, NOW(6), NOW(6)
FROM seq;

-- ── (5) 상품 이미지 — 상품당 대표 이미지 1행(URL 문자열만) → 상세 API 의 product_images 조회 ──
--   Product 엔티티엔 이미지 연관관계가 없어 목록/검색/카테고리는 product_images 를 조회하지 않는다.
--   상세(getProduct)만 findAllByProductIdOrderBySortOrderAsc 로 1회 조회 → 이 행들이 실제로 물려온다.
--   실제 파일 없이 URL 문자열만 넣는다(부하는 문자열 크기뿐이라 가볍다). 상품 1건당 1행 = 상품 수만큼 생성.
INSERT INTO product_images (representative, sort_order, created_at, updated_at, product_id, image_url)
SELECT b'1', 0, NOW(6), NOW(6), p.id,
       CONCAT('https://dummy.loadtest.local/images/', p.id, '/main.jpg')
FROM products p
WHERE p.member_id = @seller_id
  AND NOT EXISTS (SELECT 1 FROM product_images pi WHERE pi.product_id = p.id);

-- ── (6) 찜(favorites) @favorite_rows 건 → GET /api/members/me/favorites ─────────
--   read-load.js 의 '내 찜 목록' 타깃. 서버가 최근순 최대 200건(Limit.of(200))만 반환하므로
--   200건을 채워 목록 응답을 "가득 찬" 최악 케이스로 만든다.
--   조회 쿼리(findMyFavoritesWithProduct)가 삭제·숨김 상품을 제외(정책A: p.deletedAt IS NULL AND p.hidden=false)
--   하므로 반드시 정상 상품만 고른다. seller 소유라 자기찜 차단(있다면)에도 안 걸린다.
--   유니크 uk_favorites_member_product(member_id, product_id): 상품이 전부 달라야 하므로 서로 다른 id 를 뽑는다.
INSERT INTO favorites (created_at, updated_at, member_id, product_id)
SELECT NOW(6) - INTERVAL p.r SECOND, NOW(6), @favorite_owner, p.id
FROM (
  SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS r
  FROM products
  WHERE member_id = @seller_id AND deleted_at IS NULL AND hidden = b'0'
) p
WHERE p.r <= @favorite_rows
  AND NOT EXISTS (SELECT 1 FROM favorites f
                  WHERE f.member_id = @favorite_owner AND f.product_id = p.id);

-- ── (7) 채팅방 + 메시지 → GET /api/notifications/unread-count 의 무거운 경로 ─────
--   getUnreadCount() = countByRecipient_IdAndIsReadFalse(가벼운 COUNT) + getMyRooms()(무거운 3쿼리:
--   findMyChatRooms + findLatestPerRoom(방별 MAX) + countUnreadPerRoom(join+group)). (4)의 알림 3000건은
--   앞쪽 COUNT 만 부풀릴 뿐, getMyRooms 는 채팅방이 있어야 실제로 돈다 → 방/메시지를 시드해 이 경로를 태운다.
--   방 마커 = seller_id=@seller_id(로드테스트 전용 판매자라 이 시드에서만 생성됨). buyer=@chat_buyer(read-load 계정).
--   유니크 (product_id, buyer_id): buyer 가 고정이라 방마다 상품이 달라야 하므로 서로 다른 정상 상품을 뽑는다.
INSERT INTO chat_rooms (created_at, updated_at, buyer_id, seller_id, product_id,
                        buyer_last_read_message_id, seller_last_read_message_id)
SELECT NOW(6) - INTERVAL p.r SECOND, NOW(6), @chat_buyer, @seller_id, p.id, NULL, NULL
FROM (
  SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS r
  FROM products
  WHERE member_id = @seller_id AND deleted_at IS NULL AND hidden = b'0'
) p
WHERE p.r <= @chat_rooms
  AND NOT EXISTS (SELECT 1 FROM chat_rooms cr
                  WHERE cr.buyer_id = @chat_buyer AND cr.product_id = p.id);

--   방당 @chat_msgs 건. 홀수 n = 상대(seller)발신 → buyer_last_read(NULL→0)보다 뒤라 전부 "안읽음"으로 집계
--   (countUnreadPerRoom: sender<>me AND id>COALESCE(buyer_last_read,0)) → 각 방 unreadCount>0 이 되어
--   getMyRooms 의 unread 필터를 통과한다. 짝수 n = 본인(buyer)발신(읽음 기준선).
INSERT INTO chat_messages (created_at, updated_at, chat_room_id, sender_id, content)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @chat_msgs)
SELECT NOW(6) - INTERVAL s.n SECOND, NOW(6), cr.id,
       CASE WHEN s.n % 2 = 1 THEN @seller_id ELSE @chat_buyer END,
       CONCAT('부하테스트 채팅 메시지 방', cr.id, ' #', s.n)
FROM chat_rooms cr
CROSS JOIN seq s
WHERE cr.seller_id = @seller_id AND cr.buyer_id = @chat_buyer
  AND NOT EXISTS (SELECT 1 FROM chat_messages cm WHERE cm.chat_room_id = cr.id);

-- ── 결과 요약 ────────────────────────────────────────────────────────────────
SELECT
  (SELECT COUNT(*) FROM products      WHERE member_id = @seller_id)               AS seed_products,
  (SELECT MAX(id)  FROM products)                                                 AS max_product_id,   -- ← product_list_cursor.js 의 MAX_PRODUCT_ID 로 넣을 값
  (SELECT COUNT(*) FROM product_images pi JOIN products p ON p.id = pi.product_id
     WHERE p.member_id = @seller_id)                                              AS seed_images,
  (SELECT COUNT(*) FROM reports       WHERE reporter_id = @reporter_id)           AS seed_reports,
  (SELECT COUNT(*) FROM comments      WHERE content LIKE '부하테스트 더미 댓글%')  AS seed_comments,
  (SELECT COUNT(*) FROM notifications WHERE message LIKE '%부하테스트 더미 알림%') AS seed_notifications,
  (SELECT COUNT(*) FROM favorites     WHERE member_id = @favorite_owner)          AS seed_favorites,
  (SELECT COUNT(*) FROM chat_rooms    WHERE seller_id = @seller_id)               AS seed_chat_rooms,
  (SELECT COUNT(*) FROM chat_messages cm JOIN chat_rooms cr ON cr.id = cm.chat_room_id
     WHERE cr.seller_id = @seller_id)                                             AS seed_chat_messages;

-- ============================================================================
-- CLEANUP — 테스트 종료 후 실행 (자식→부모 순서. FK 때문에 순서 중요)
-- ----------------------------------------------------------------------------
-- DELETE FROM comments      WHERE content LIKE '부하테스트 더미 댓글%';
-- DELETE FROM notifications WHERE message LIKE '%부하테스트 더미 알림%';
-- DELETE FROM reports       WHERE reporter_id = 2 AND content LIKE '부하테스트 신고%';
-- DELETE cm FROM chat_messages cm JOIN chat_rooms cr ON cr.id = cm.chat_room_id
--   WHERE cr.seller_id = (SELECT id FROM members WHERE email='loadtest-seller@dongne.test');
-- DELETE FROM chat_rooms    WHERE seller_id = (SELECT id FROM members WHERE email='loadtest-seller@dongne.test');
-- DELETE FROM favorites     WHERE member_id = 2 AND product_id IN
--   (SELECT id FROM products WHERE member_id = (SELECT id FROM members WHERE email='loadtest-seller@dongne.test'));
-- DELETE pi FROM product_images pi JOIN products p ON p.id = pi.product_id
--   WHERE p.member_id = (SELECT id FROM members WHERE email='loadtest-seller@dongne.test');
-- DELETE FROM products      WHERE member_id = (SELECT id FROM members WHERE email='loadtest-seller@dongne.test');
-- DELETE FROM members       WHERE email = 'loadtest-seller@dongne.test';
-- ============================================================================
