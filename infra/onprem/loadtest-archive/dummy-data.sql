-- ============================================================================
-- 부하테스트용 더미 데이터 생성 (MySQL 8, dongne_market)
-- 재귀 CTE로 대량 INSERT. 실행 전 대상 id가 실제로 존재하는지 확인할 것(FK 제약).
--
-- 실행 (infra/onprem/loadtest 에서, 컨테이너 dongne-mysql 기동 상태):
--   Get-Content dummy-data.sql | docker exec -i dongne-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" dongne_market'
--
-- 정리(테스트 종료 후): 파일 맨 아래 "롤백" 블록의 두 DELETE 실행.
-- ============================================================================

-- 재귀 3000회 > 기본 상한(1000)이라 반드시 올려줘야 한다.
SET SESSION cte_max_recursion_depth = 10000;

-- ── 대상 id (실제 존재하는 값으로 맞출 것) ──────────────────────────────────
SET @rows        = 3000;  -- 생성할 건수
SET @product_id  = 1;     -- 댓글/알림이 참조할 상품 (products.id 존재 필수)
SET @author_id   = 1;     -- 댓글 작성자 (members.id 존재 필수)
SET @recipient_id = 2;    -- 알림 수신자 = k6 로그인 계정의 members.id

-- ── (A) 댓글 3000건 ────────────────────────────────────────────────────────
-- 타깃: GET /api/products/{productId}/comments (삭제 안 된 전체를 member fetch join 으로 조회)
-- @product_id 한 상품에 몰아넣어 "인기글 댓글 목록" 응답 크기를 키운다.
INSERT INTO comments (content, member_id, product_id, created_at, updated_at, deleted_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @rows
)
SELECT CONCAT('부하테스트 더미 댓글 #', n),
       @author_id, @product_id,
       NOW(6) - INTERVAL n SECOND,   -- created_at 을 조금씩 과거로 흩뿌림
       NOW(6) - INTERVAL n SECOND,
       NULL
FROM seq;

-- ── (B) 안읽은 알림 3000건 ─────────────────────────────────────────────────
-- 타깃: GET /api/notifications/unread-count (countByRecipient_IdAndIsReadFalse) + 목록 조회
-- @recipient_id 의 안읽은 알림을 부풀려 카운트 쿼리 부하를 준다.
-- (notifications.product_id 는 FK 없음 — 아무 bigint 가능하나 실 상품 id 사용)
INSERT INTO notifications (is_read, message, type, product_id, recipient_id, last_notified_at, created_at, updated_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @rows
)
SELECT b'0',                          -- is_read = false
       CONCAT('💬 부하테스트 더미 알림 #', n),
       'COMMENT',
       @product_id, @recipient_id,
       NOW(6) - INTERVAL n SECOND,    -- last_notified_at (NOT NULL)
       NOW(6), NOW(6)
FROM seq;

-- 확인
SELECT
  (SELECT COUNT(*) FROM comments WHERE content LIKE '부하테스트 더미 댓글%')      AS dummy_comments,
  (SELECT COUNT(*) FROM notifications WHERE message LIKE '%부하테스트 더미 알림%') AS dummy_notifications;

-- ============================================================================
-- 롤백(정리) — 테스트가 끝나면 실행
-- ----------------------------------------------------------------------------
-- DELETE FROM comments      WHERE content LIKE '부하테스트 더미 댓글%';
-- DELETE FROM notifications WHERE message LIKE '%부하테스트 더미 알림%';
-- ============================================================================
