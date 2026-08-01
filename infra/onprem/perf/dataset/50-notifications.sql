-- 알림. 헤더의 안읽음 배지(GET /api/notifications/unread-count)가 핵심 대상이다.
--
-- 이 엔드포인트는 **모든 화면에서 폴링된다** — 고빈도이면서 내부적으로 조인을 재사용하는
-- 구조라 "고빈도 × 숨은 조인" 조합이 된다. 한 사람의 안읽음 알림이 많아질수록 무거워지므로
-- 테스트 계정에 집중시킨다.
--
-- 실행:  /bin/bash -c 'source lib/common.sh; mysql_file dataset/50-notifications.sql'
-- 순서: 10-products.sql 뒤.

-- ── 파라미터 ────────────────────────────────────────────────────────────────
SET @my_unread  = 3000;    -- 테스트 계정의 안읽음 알림 수 (배지가 세야 하는 양)
SET @my_read    = 2000;    -- 읽은 알림. 목록 조회는 둘 다 훑는다
SET @spread_per = 100;     -- 볼륨 회원 1인당 알림 수 (테이블 전체 크기)

SET @test_member = (SELECT id FROM members WHERE email = 'onprem-test@example.com' LIMIT 1);
-- 상품·회원 id 는 연속이 아니다(중간에 지운 자리에 구멍이 있다). 산술로 id 를 만들면
-- FK 위반이 난다(실제로 겪었다). 실제 id 에 일련번호를 붙인 매핑 테이블을 만들어 조인으로 고른다.
DROP TEMPORARY TABLE IF EXISTS _prod;
CREATE TEMPORARY TABLE _prod (rn INT PRIMARY KEY, id BIGINT);
INSERT INTO _prod (rn, id)
SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM products WHERE title LIKE '[perf]%';

DROP TEMPORARY TABLE IF EXISTS _mem;
CREATE TEMPORARY TABLE _mem (rn INT PRIMARY KEY, id BIGINT);
INSERT INTO _mem (rn, id)
SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM members WHERE email LIKE 'perf-%';

SET @p_cnt = (SELECT COUNT(*) FROM _prod);
SET @m_cnt = (SELECT COUNT(*) FROM _mem);

DROP TEMPORARY TABLE IF EXISTS _seq5;
CREATE TEMPORARY TABLE _seq5 (n INT PRIMARY KEY);
INSERT INTO _seq5 (n)
SELECT (d5.n*10000 + d4.n*1000 + d3.n*100 + d2.n*10 + d1.n) + 1
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d4
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d5;

-- ── 1. 테스트 계정 — 안읽음 (배지가 세는 대상) ──────────────────────────────
INSERT INTO notifications (recipient_id, product_id, type, message, is_read, last_notified_at, created_at, updated_at)
SELECT @test_member, p.id,
       CASE WHEN s.n % 3 = 0 THEN 'PRICE_CHANGE' ELSE 'COMMENT' END,
       CONCAT('[perf] 알림 ', s.n, ' — 관심 상품에 새 소식이 있습니다.'),
       b'0', NOW(6) - INTERVAL (s.n % 30) DAY, NOW(6) - INTERVAL (s.n % 30) DAY, NOW(6)
FROM _seq5 s JOIN _prod p ON p.rn = 1 + ((s.n * 11) % @p_cnt)
WHERE s.n <= @my_unread AND @test_member IS NOT NULL;

-- ── 2. 테스트 계정 — 읽음 (목록 조회는 읽은 것도 훑는다) ────────────────────
INSERT INTO notifications (recipient_id, product_id, type, message, is_read, last_notified_at, created_at, updated_at)
SELECT @test_member, p.id, 'COMMENT',
       CONCAT('[perf] 읽은 알림 ', s.n),
       b'1', NOW(6) - INTERVAL (30 + s.n % 60) DAY, NOW(6) - INTERVAL (30 + s.n % 60) DAY, NOW(6)
FROM _seq5 s JOIN _prod p ON p.rn = 1 + ((s.n * 23) % @p_cnt)
WHERE s.n <= @my_read AND @test_member IS NOT NULL;

-- ── 3. 볼륨 회원에게 분산 (테이블 전체 크기) ────────────────────────────────
INSERT INTO notifications (recipient_id, product_id, type, message, is_read, last_notified_at, created_at, updated_at)
SELECT m.id, p.id, 'COMMENT',
       CONCAT('[perf] 알림 ', s.n), IF(s.n % 2 = 0, b'1', b'0'),
       NOW(6) - INTERVAL (s.n % 90) DAY, NOW(6) - INTERVAL (s.n % 90) DAY, NOW(6)
FROM _mem m CROSS JOIN _seq5 s
JOIN _prod p ON p.rn = 1 + (((m.id * 37) + s.n * 7) % @p_cnt)
WHERE s.n <= @spread_per;

DROP TEMPORARY TABLE IF EXISTS _seq5;
DROP TEMPORARY TABLE IF EXISTS _prod;
DROP TEMPORARY TABLE IF EXISTS _mem;

SELECT CONCAT('알림 — 전체 ', (SELECT COUNT(*) FROM notifications),
              '건 / 테스트 계정 안읽음 ',
              (SELECT COUNT(*) FROM notifications WHERE recipient_id = @test_member AND is_read = b'0'),
              '건') AS result;
