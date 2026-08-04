-- 부하테스트 전용 데이터. **마커가 `[load]` 라 perf 의 `[perf]` 데이터와 섞이지 않는다.**
-- 둘이 DB 에 공존해도 서로를 건드리지 않고, 정리도 각자 마커로만 한다.
--
-- 볼륨은 10만 건으로 고정한다. 부하테스트는 **동시성**을 재는 것이라 볼륨이 변수가 되면 안 된다.
-- (perf 의 볼륨 테스트에서 확인한 버퍼풀 무릎은 10만→20만 구간이므로, 그 아래에 둔다.)
--
-- 실행:  ./setup/load-data.sh

-- ── 파라미터 ────────────────────────────────────────────────────────────────
SET @products    = 100000;  -- 상품. 버퍼풀 무릎(20만) 아래로 고정한다
SET @members     = 300;     -- 상품 소유자 풀
SET @comments_hot = 500;    -- 인기 상품 1건의 댓글 (상세 화면이 반환하는 양)

-- BCrypt 해시는 SQL 로 만들 수 없다. 기존 계정의 해시를 복사한다 —
-- 이 계정들은 상품 소유자 역할만 하고 로그인하지 않는다.
SET @pw_hash = (SELECT password FROM members WHERE password IS NOT NULL LIMIT 1);
SET @m_offset = (SELECT COUNT(*) FROM members WHERE email LIKE 'load-%');

DROP TEMPORARY TABLE IF EXISTS _n;
CREATE TEMPORARY TABLE _n (n INT PRIMARY KEY);
INSERT INTO _n (n)
SELECT (d6.n*100000 + d5.n*10000 + d4.n*1000 + d3.n*100 + d2.n*10 + d1.n) + 1
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d4
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d5
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d6;

-- ── 1. 회원 ─────────────────────────────────────────────────────────────────
INSERT INTO members (email, password, nickname, role, status, local_login_enabled, created_at, updated_at)
SELECT CONCAT('load-', LPAD(n, 6, '0'), '@loadtest.local'), @pw_hash,
       CONCAT('부하테스트유저', LPAD(n, 6, '0')),
       'ROLE_USER', 'ACTIVE', b'1', NOW(6), NOW(6)
FROM _n WHERE n <= @members AND @m_offset = 0;

-- ── 2. 편중 대상 ────────────────────────────────────────────────────────────
-- 균등 분포는 실제 서비스와 다르다. 지역을 고르게 뿌리면 인덱스 선택도가 비현실적으로 좋아져
-- 결과가 낙관적으로 왜곡된다. 상위 20개 동네에 60% 를 몰아준다.
DROP TEMPORARY TABLE IF EXISTS _hot_rg;
CREATE TEMPORARY TABLE _hot_rg (rn INT PRIMARY KEY, id BIGINT);
INSERT INTO _hot_rg SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM regions WHERE level = 3 LIMIT 20;

DROP TEMPORARY TABLE IF EXISTS _all_rg;
CREATE TEMPORARY TABLE _all_rg (rn INT PRIMARY KEY, id BIGINT);
INSERT INTO _all_rg SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM regions WHERE level = 3;

DROP TEMPORARY TABLE IF EXISTS _mem;
CREATE TEMPORARY TABLE _mem (rn INT PRIMARY KEY, id BIGINT);
INSERT INTO _mem SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM members WHERE email LIKE 'load-%';

SET @hot_cnt = (SELECT COUNT(*) FROM _hot_rg);
SET @all_cnt = (SELECT COUNT(*) FROM _all_rg);
SET @mem_cnt = (SELECT COUNT(*) FROM _mem);
SET @cat_cnt = (SELECT COUNT(*) FROM categories);

-- ── 3. 상품 ─────────────────────────────────────────────────────────────────
INSERT INTO products (member_id, category_id, region_id, title, description, price,
                      view_count, favorite_count, hidden, trade_status, created_at, updated_at)
SELECT m.id,
       CASE WHEN s.n % 10 < 4 THEN 1 + (s.n % 2) * 3 ELSE 1 + (s.n % @cat_cnt) END,
       CASE WHEN s.n % 10 < 6
            THEN (SELECT id FROM _hot_rg WHERE rn = 1 + (s.n % @hot_cnt))
            ELSE (SELECT id FROM _all_rg WHERE rn = 1 + (s.n % @all_cnt)) END,
       CONCAT('[load] 부하테스트 상품 ', LPAD(s.n, 7, '0')),
       CONCAT('부하테스트용 더미 설명입니다. ',
              REPEAT('상세 설명 문장을 채워 실제 본문 길이에 가깝게 만듭니다. ', 4),
              '일련번호 ', s.n),
       5000 + (s.n % 100) * 5000,
       CASE WHEN s.n % 100 = 0 THEN 5000 + (s.n % 5000) ELSE s.n % 100 END,
       s.n % 30, b'0',
       CASE WHEN s.n % 20 = 0 THEN 'RESERVED' WHEN s.n % 50 = 0 THEN 'COMPLETED' ELSE 'ON_SALE' END,
       CASE WHEN s.n % 2 = 0 THEN NOW(6) - INTERVAL (s.n % 30) DAY
            ELSE NOW(6) - INTERVAL (30 + (s.n % 335)) DAY END,
       NOW(6)
FROM _n s JOIN _mem m ON m.rn = 1 + (s.n % @mem_cnt)
WHERE s.n <= @products;

-- ── 4. 인기 상품에 댓글 ─────────────────────────────────────────────────────
-- 상세 화면 프로브가 댓글도 부르므로, 한 상품에 몰아 실제 무게를 만든다.
SET @hot_product = (SELECT id FROM products WHERE title LIKE '[load]%' ORDER BY view_count DESC LIMIT 1);

INSERT INTO comments (member_id, product_id, content, created_at, updated_at)
SELECT m.id, @hot_product,
       CONCAT('[load] 댓글 ', s.n, ' — 아직 판매 중인가요?'),
       NOW(6) - INTERVAL (s.n % 60) DAY, NOW(6)
FROM _n s JOIN _mem m ON m.rn = 1 + (s.n % @mem_cnt)
WHERE s.n <= @comments_hot;

DROP TEMPORARY TABLE IF EXISTS _n;
DROP TEMPORARY TABLE IF EXISTS _hot_rg;
DROP TEMPORARY TABLE IF EXISTS _all_rg;
DROP TEMPORARY TABLE IF EXISTS _mem;

SELECT CONCAT('적재 완료 — 상품 ', (SELECT COUNT(*) FROM products WHERE title LIKE '[load]%'),
              ' / 회원 ', (SELECT COUNT(*) FROM members WHERE email LIKE 'load-%'),
              ' / 인기 상품(id ', @hot_product, ') 댓글 ',
              (SELECT COUNT(*) FROM comments WHERE product_id = @hot_product)) AS result;
